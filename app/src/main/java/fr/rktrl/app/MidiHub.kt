package fr.rktrl.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Gestion MIDI : appareils USB / virtuels / Bluetooth LE (BLE-MIDI).
 * - envoie CC, notes, horloge vers le port d'entrée de l'appareil
 * - écoute les CC renvoyés par l'appareil (pour connaître la valeur réelle des paramètres)
 */
class MidiHub(private val ctx: Context) {

    companion object {
        val BLE_MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4700C")
    }

    private val main = Handler(Looper.getMainLooper())
    val manager: MidiManager? = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val lock = Any()
    private var device: MidiDevice? = null
    private var input: MidiInputPort? = null
    private var output: MidiOutputPort? = null

    var connectedName: String? = null
        private set
    val isConnected: Boolean get() = input != null

    val statusListeners = ArrayList<() -> Unit>()
    val ccListeners = ArrayList<(Int, Int, Int) -> Unit>()

    // ---------- réception ----------
    private val receiver = object : MidiReceiver() {
        private var status = 0
        private var d1 = -1

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (n in offset until offset + count) {
                val b = msg[n].toInt() and 0xFF
                if (b >= 0xF8) continue                      // temps réel : ignoré
                if (b >= 0x80) {                             // octet de statut
                    status = if (b < 0xF0) b else 0
                    d1 = -1
                    continue
                }
                if (status == 0) continue
                val type = status and 0xF0
                val dataLen = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (dataLen == 1) { d1 = -1; continue }
                if (d1 < 0) { d1 = b; continue }
                val first = d1
                d1 = -1                                      // running status : on reste sur le même statut
                if (type == 0xB0) {
                    val ch = (status and 0x0F) + 1
                    main.post { ccListeners.forEach { it(ch, first, b) } }
                }
            }
        }
    }

    init {
        @Suppress("DEPRECATION")
        manager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                if (device?.info?.id == info.id) {
                    close()
                }
            }
        }, main)
    }

    // ---------- appareils ----------
    @Suppress("DEPRECATION")
    fun listDevices(): List<MidiDeviceInfo> =
        manager?.devices?.filter { it.inputPortCount > 0 } ?: emptyList()

    fun nameOf(info: MidiDeviceInfo): String {
        val pr = info.properties
        return pr.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: pr.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI #${info.id}"
    }

    fun openInfo(info: MidiDeviceInfo, onFail: () -> Unit) {
        val m = manager ?: return onFail()
        val name = nameOf(info)
        m.openDevice(info, { dev -> if (dev == null) onFail() else attach(dev, name) }, main)
    }

    @SuppressLint("MissingPermission")
    fun openBluetooth(bt: BluetoothDevice, name: String, onFail: () -> Unit) {
        val m = manager ?: return onFail()
        try {
            m.openBluetoothDevice(bt, { dev -> if (dev == null) onFail() else attach(dev, name) }, main)
        } catch (e: SecurityException) {
            onFail()
        }
    }

    private fun attach(dev: MidiDevice, name: String) {
        close()
        synchronized(lock) {
            device = dev
            val info = dev.info
            if (info.inputPortCount > 0) input = dev.openInputPort(0)
            if (info.outputPortCount > 0) {
                output = dev.openOutputPort(0)
                output?.connect(receiver)
            }
            connectedName = name
            isBle = info.type == MidiDeviceInfo.TYPE_BLUETOOTH
        }
        clearQueue()
        notifyStatus()
    }

    fun close() {
        synchronized(lock) {
            try { output?.disconnect(receiver) } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            output = null; input = null; device = null; connectedName = null
        }
        clearQueue()
        notifyStatus()
    }

    private fun notifyStatus() {
        main.post { statusListeners.forEach { it() } }
    }

    // ---------- scan Bluetooth LE MIDI ----------
    private var scanCb: ScanCallback? = null
    private var scanStop: Runnable? = null

    /** Lance un scan BLE-MIDI. Renvoie un message d'erreur, ou null si le scan a démarré. */
    @SuppressLint("MissingPermission")
    fun startBleScan(onFound: (BluetoothDevice, String) -> Unit, onEnd: () -> Unit): String? {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return tr("Bluetooth indisponible sur cet appareil", "Bluetooth not available on this device")
        val adapter = bm.adapter ?: return tr("Bluetooth indisponible sur cet appareil", "Bluetooth not available on this device")
        if (!adapter.isEnabled) return tr("Active le Bluetooth puis réessaie", "Turn Bluetooth on and try again")
        val scanner = adapter.bluetoothLeScanner ?: return tr("Scanner Bluetooth indisponible", "Bluetooth scanner not available")
        stopBleScan()

        // Pas de filtre : certains instruments (ex. Yamaha SEQTRAK) n'annoncent pas le service
        // BLE-MIDI dans leur publicité. On liste tous les appareils nommés ; ceux qui annoncent
        // BLE-MIDI sont marqués.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val rec = result.scanRecord
                val name = try { rec?.deviceName ?: d.name } catch (e: SecurityException) { null } ?: return
                val isMidi = rec?.serviceUuids?.any { it.uuid == BLE_MIDI_SERVICE } == true
                main.post { onFound(d, if (isMidi) "$name ♪MIDI" else name) }
            }
        }
        return try {
            scanner.startScan(null, settings, cb)
            scanCb = cb
            val stop = Runnable { stopBleScan(); onEnd() }
            scanStop = stop
            main.postDelayed(stop, 15_000)
            null
        } catch (e: SecurityException) {
            tr("Autorisation Bluetooth refusée", "Bluetooth permission denied")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        scanStop?.let { main.removeCallbacks(it) }
        scanStop = null
        val cb = scanCb ?: return
        scanCb = null
        try {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
    }

    // ---------- envoi ----------
    // ---------- envoi : file d'attente + thread dédié ----------
    // Le Bluetooth MIDI a un débit limité et Android bloque l'appelant quand son tampon est plein.
    // Avant, une rafale (effets du Destroy Mode sur 16 canaux…) saturait la liaison : l'interface
    // se figeait et le SEQTRAK finissait par se déconnecter. Désormais :
    //  - un seul thread écrit vers l'instrument (les autres ne sont jamais bloqués) ;
    //  - CC et pitch bend sont « fusionnés » : seule la dernière valeur de chaque contrôle part ;
    //  - en Bluetooth, le débit est plafonné et, en cas d'embouteillage, les nouvelles notes
    //    sont abandonnées (jamais les fins de notes, pour ne pas laisser de note coincée).

    @Volatile var isBle = false
        private set
    private val qLock = Object()
    private val urgent = ArrayDeque<ByteArray>()
    private val merged = LinkedHashMap<Int, ByteArray>()
    private val outThread = Thread({ senderLoop() }, "rktrl-midi-out").also {
        it.isDaemon = true
        it.priority = Thread.MAX_PRIORITY
        it.start()
    }

    private fun clearQueue() = synchronized(qLock) { urgent.clear(); merged.clear() }

    private fun enqueue(bytes: ByteArray) {
        if (input == null) return
        synchronized(qLock) {
            if (urgent.size > 192) {
                val st = bytes[0].toInt() and 0xF0
                val isNoteOn = st == 0x90 && bytes.size > 2 && bytes[2].toInt() != 0
                if (isNoteOn) return                     // embouteillage : on saute cette note
                if (bytes[0].toInt() and 0xFF == 0xF8 && urgent.size > 256) return   // horloge en retard : inutile
            }
            urgent.addLast(bytes)
            qLock.notify()
        }
    }

    private fun enqueueMerged(key: Int, bytes: ByteArray) {
        if (input == null) return
        synchronized(qLock) {
            merged.remove(key)
            merged[key] = bytes
            qLock.notify()
        }
    }

    private fun senderLoop() {
        val buf = ByteArray(4096)
        while (true) {
            var len = 0
            val ble = isBle
            val budget = if (ble) 48 else buf.size          // octets par paquet
            synchronized(qLock) {
                while (urgent.isEmpty() && merged.isEmpty()) {
                    try { qLock.wait() } catch (_: InterruptedException) {}
                }
                while (urgent.isNotEmpty() && len + urgent.first().size <= budget) {
                    val m = urgent.removeFirst()
                    System.arraycopy(m, 0, buf, len, m.size); len += m.size
                }
                val it = merged.entries.iterator()
                while (it.hasNext()) {
                    val m = it.next().value
                    if (len + m.size > budget) break
                    System.arraycopy(m, 0, buf, len, m.size); len += m.size
                    it.remove()
                }
            }
            if (len > 0) {
                val port = synchronized(lock) { input }
                try { port?.send(buf, 0, len) } catch (_: Exception) {}
            }
            if (ble) try { Thread.sleep(6) } catch (_: InterruptedException) {}
        }
    }

    fun send(bytes: ByteArray) = enqueue(bytes)

    fun sendCC(ch: Int, cc: Int, v: Int) = enqueueMerged(((0xB0 or ((ch - 1) and 0x0F)) shl 8) or (cc and 0x7F),
        byteArrayOf((0xB0 or ((ch - 1) and 0x0F)).toByte(), (cc and 0x7F).toByte(), (v and 0x7F).toByte()))

    fun pitchBend(ch: Int, value: Int) {
        val b = value.coerceIn(0, 16383)
        enqueueMerged((0xE0 or ((ch - 1) and 0x0F)) shl 8,
            byteArrayOf((0xE0 or ((ch - 1) and 0x0F)).toByte(), (b and 0x7F).toByte(), ((b shr 7) and 0x7F).toByte()))
    }

    fun noteOn(ch: Int, note: Int, vel: Int) = enqueue(byteArrayOf(
        (0x90 or ((ch - 1) and 0x0F)).toByte(), (note and 0x7F).toByte(), (vel and 0x7F).toByte()))

    fun noteOff(ch: Int, note: Int) = enqueue(byteArrayOf(
        (0x80 or ((ch - 1) and 0x0F)).toByte(), (note and 0x7F).toByte(), 0))

    fun realtime(b: Int) = enqueue(byteArrayOf(b.toByte()))
}
