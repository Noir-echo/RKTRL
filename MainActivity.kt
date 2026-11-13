package fr.rktrl.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_BLE = 42
        private const val REQ_SAVE = 43
        private const val REQ_LOAD = 44
    }

    private lateinit var midi: MidiHub
    private lateinit var seq: Sequencer
    private lateinit var ctrl: ControllerPage
    private lateinit var seqPage: SequencerPage
    private lateinit var fxPage: DestroyPage
    private lateinit var fx: Destroyer
    private lateinit var root: FrameLayout
    private var page = 0
    private var pendingBle: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Tdr.init(this)
        Lang.current = Store.loadLang(this)
        Store.loadSettings(this)
        Tdr.apply()

        midi = MidiHub(this)
        seq = Sequencer(midi)
        Store.loadSeq(this, seq)
        fx = Destroyer(midi, seq)
        Store.loadFx(this, fx)
        seq.fx = fx
        fx.knownCC = { ch, cc -> ctrl.knownValue(ch, cc) }

        root = FrameLayout(this)
        root.setBackgroundColor(Tdr.BG)
        setContentView(root)

        midi.statusListeners.add { updateStatus() }
        midi.ccListeners.add { ch, cc, v -> ctrl.onExternalCC(ch, cc, v); fx.observeCC(ch, cc, v) }
        seq.onStep = {
            seqPage.onStep()
            ctrl.refreshTransport()
            fxPage.refreshTransport()
        }

        buildPages()

        if (midi.manager == null) {
            toast(tr("Cet appareil ne gère pas le MIDI Android", "This device does not support Android MIDI"))
        }
        hideSystemBars()
    }

    /** (Re)construit les deux pages — au démarrage, après un changement de langue ou un chargement. */
    private fun buildPages() {
        root.removeAllViews()
        root.setBackgroundColor(Tdr.BG)
        window.decorView.setBackgroundColor(Tdr.BG)
        ctrl = ControllerPage(this, midi, seq)
        seqPage = SequencerPage(this, seq)
        fxPage = DestroyPage(this, fx, seq)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(ctrl.view, FrameLayout.LayoutParams(mp, mp))
        root.addView(seqPage.view, FrameLayout.LayoutParams(mp, mp))
        root.addView(fxPage.view, FrameLayout.LayoutParams(mp, mp))

        ctrl.onGoSequencer = { showPage(1) }
        ctrl.onGoDestroy = { showPage(2) }
        seqPage.onGoController = { showPage(0) }
        fxPage.onNormal = { showPage(0) }
        fxPage.onSave = { Store.saveFx(this, fx) }
        fxPage.onTempo = { seqPage.refreshTempo(); listOf(ctrl.header, seqPage.header, fxPage.header).forEach { it.invalidate() } }
        ctrl.onLogo = { showOptions() }
        seqPage.onLogo = { showOptions() }
        fxPage.onLogo = { showOptions() }
        for (h in listOf(ctrl.header, seqPage.header, fxPage.header)) {
            h.onTab = { showPage(it) }
            h.bpm = { seq.bpm }
        }
        ctrl.onLink = { showDeviceDialog() }
        seqPage.onLink = { showDeviceDialog() }
        fxPage.onLink = { showDeviceDialog() }

        showPage(page)
        updateStatus()
    }

    private fun showPage(i: Int) {
        page = i
        if (i == 1) seqPage.refreshTempo()
        ctrl.view.visibility = if (i == 0) View.VISIBLE else View.GONE
        seqPage.view.visibility = if (i == 1) View.VISIBLE else View.GONE
        fxPage.view.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 2) fxPage.onShow()
        currentFocus?.clearFocus()
    }

    private fun updateStatus() {
        val name = midi.connectedName
        val s = if (name != null) "LINK // ${name.uppercase()}"
                else tr("NO LINK // TOUCHER POUR CONNECTER", "NO LINK // TAP TO CONNECT")
        for (h in listOf(ctrl.header, seqPage.header, fxPage.header)) {
            h.status = s
            h.linked = midi.isConnected
            h.deviceName = name
        }
    }

    // ------------------------------------------------------------ OPTIONS
    private fun showOptions() {
        val d = TdrDialog(this, "OPTIONS // RKTRL", Tdr.YELLOW)

        d.section(tr("LANGUE", "LANGUAGE"))
        val langs = arrayOf("FRANÇAIS", "ENGLISH", "AUREBESH")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        langs.forEachIndexed { i, name ->
            val b = TdrButton(this, name, Tdr.YELLOW)
            b.latin = true                      // toujours lisible, même en Aurebesh
            b.active = Lang.current == i
            b.onClick {
                if (Lang.current != i) {
                    d.dismiss()
                    setLanguage(i)
                }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.rightMargin = dpi(6)
            box.addView(b, lp)
        }
        d.row(box, 42)
        if (Lang.current == Lang.AUREBESH) {
            d.note("Aurebesh font: SilvinoR, SIL Open Font License 1.1", Tdr.DIM)
        }

        d.section(tr("COULEURS", "COLOURS"))
        run {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val bP = TdrButton(this, tr("FOND", "BACKGROUND"), Tdr.YELLOW).onClick {
                pickColor(this, tr("FOND", "BACKGROUND"), Settings.fieldPaper) { col -> Settings.fieldPaper = col; d.dismiss(); applyTheme(Themes.FIELD); showOptions() }
            }
            val bA = TdrButton(this, "ACCENT", Tdr.YELLOW).onClick {
                pickColor(this, "ACCENT", Settings.fieldAccent) { col -> Settings.fieldAccent = col; d.dismiss(); applyTheme(Themes.FIELD); showOptions() }
            }
            val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); l1.rightMargin = dpi(6)
            row.addView(bP, l1)
            row.addView(bA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            d.row(row, 40)
            d.note(tr("Un fond foncé passe l'écriture en clair.", "A dark background switches the text to light."))
        }

        d.section("DESTROY MODE")
        val bVis = TdrButton(this, tr("ANIMATIONS GÉOMÉTRIQUES DU PAD X/Y", "GEOMETRIC ANIMATIONS IN THE X/Y PAD"), Tdr.MAGENTA)
        bVis.active = Settings.fxVisuals
        bVis.onClick {
            Settings.fxVisuals = !Settings.fxVisuals
            bVis.active = Settings.fxVisuals
            Store.saveSettings(this)
        }
        d.row(bVis, 38)

        d.section("MIDI")
        val bClock = TdrButton(this, tr("ENVOYER L'HORLOGE MIDI (START / STOP / CLOCK)", "SEND MIDI CLOCK (START / STOP / CLOCK)"), Tdr.CYAN)
        bClock.active = Settings.midiClock
        bClock.onClick {
            Settings.midiClock = !Settings.midiClock
            bClock.active = Settings.midiClock
            Store.saveSettings(this)
        }
        d.row(bClock, 38)
        d.note(tr("L'horloge est prise en compte au prochain PLAY.", "Clock changes apply on the next PLAY."))
        d.button(tr("NOMMER LES CANAUX MIDI", "NAME THE MIDI CHANNELS"), Tdr.CYAN) { d.dismiss(); showChannelNames() }

        d.section(tr("SAUVEGARDE", "BACKUP"))
        d.note(tr("Enregistre tout dans un fichier : boutons, fader, 16 séquences (A et B), noms des canaux, interface, Destroy Mode.",
            "Saves everything to a file: knobs, fader, 16 sequences (A and B), channel names, interface, Destroy Mode."))
        d.button(tr("SAUVEGARDER", "SAVE"), Tdr.CYAN) { d.dismiss(); startSave() }
        d.button(tr("CHARGER UNE SAUVEGARDE", "LOAD A BACKUP"), Tdr.CYAN) { d.dismiss(); startLoad() }

        d.action(tr("FERMER", "CLOSE"), Tdr.DIM) { d.dismiss() }
        d.setOnDismissListener { hideSystemBars() }
        d.show()
    }

    private fun setLanguage(l: Int) {
        ctrl.save()
        Store.saveSeq(this, seq)
        Lang.current = l
        Store.saveLang(this, l)
        buildPages()
    }

    private fun applyTheme(t: Int) {
        ctrl.save()
        Store.saveSeq(this, seq)
        Settings.theme = t
        Store.saveSettings(this)
        Tdr.apply()
        buildPages()
    }

    private fun showChannelNames() {
        val d = TdrDialog(this, tr("NOMS DES CANAUX MIDI", "MIDI CHANNEL NAMES"), Tdr.CYAN)
        d.note(tr("Ces noms s'affichent dans le contrôleur, le séquenceur et le Destroy Mode.",
            "These names are shown in the controller, the sequencer and Destroy Mode."))
        val edits = ArrayList<android.widget.EditText>()
        for (r in 0 until 8) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0..1) {
                val ch = r + k * 8
                val lab = Tdr.label(this, "%02d".format(ch + 1), 13f, Tdr.trackColor(ch))
                lab.gravity = android.view.Gravity.CENTER_VERTICAL
                row.addView(lab, LinearLayout.LayoutParams(dpi(30), ViewGroup.LayoutParams.MATCH_PARENT))
                val e = tdrEdit(this, Settings.channelNames[ch], 13f, 12)
                e.hint = tr("CANAL ${ch + 1}", "CHANNEL ${ch + 1}")
                edits.add(e)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.rightMargin = dpi(10)
                row.addView(e, lp)
            }
            d.row(row, 38)
        }
        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("VALIDER", "OK"), primary = true) {
            for (r in 0 until 8) for (k in 0..1) {
                val ch = r + k * 8
                Settings.channelNames[ch] = edits[r * 2 + k].text.toString().trim()
            }
            Store.saveSettings(this)
            d.dismiss()
            ctrl.save(); Store.saveSeq(this, seq)
            buildPages()
        }
        d.setOnDismissListener { hideSystemBars() }
        d.show()
    }

    private fun startSave() {
        ctrl.save()
        Store.saveSeq(this, seq)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        Store.saveFx(this, fx)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "rktrl-$stamp.json")
        try { startActivityForResult(i, REQ_SAVE) } catch (e: Exception) { toast(tr("Impossible d'ouvrir l'explorateur", "Cannot open file picker")) }
    }

    private fun startLoad() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try { startActivityForResult(i, REQ_LOAD) } catch (e: Exception) { toast(tr("Impossible d'ouvrir l'explorateur", "Cannot open file picker")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_SAVE -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportAll(this, seq, fx).toByteArray()) }
                toast(tr("Sauvegarde enregistrée", "Backup saved"))
            } catch (e: Exception) {
                toast(tr("Échec de la sauvegarde", "Save failed"))
            }
            REQ_LOAD -> try {
                // garde-fou : une vraie sauvegarde pèse quelques centaines de Ko au plus
                val text = contentResolver.openInputStream(uri)?.use { inp ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16384)
                    var total = 0
                    while (true) {
                        val n = inp.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 8_000_000) throw java.io.IOException("trop gros")
                        buf.write(chunk, 0, n)
                    }
                    buf.toString("UTF-8")
                } ?: ""
                if (Store.importAll(this, text, seq, fx)) {
                    Tdr.apply()
                    buildPages()
                    toast(tr("Sauvegarde chargée", "Backup loaded"))
                } else {
                    toast(tr("Ce fichier n'est pas une sauvegarde RKTRL", "This file is not an RKTRL backup"))
                }
            } catch (e: Exception) {
                toast(tr("Impossible de lire ce fichier", "Cannot read this file"))
            }
        }
    }

    // ------------------------------------------------------------ connexion
    private fun showDeviceDialog() {
        val d = TdrDialog(this, "LINK // MIDI", Tdr.CYAN)
        d.note(midi.connectedName?.let { tr("Connecté : ", "Connected: ") + it.uppercase() }
            ?: tr("Aucun instrument connecté.", "No instrument connected."), Tdr.WHITE)

        d.section(tr("USB · VIRTUELS · DÉJÀ OUVERTS", "USB · VIRTUAL · ALREADY OPEN"))
        val list = midi.listDevices()
        if (list.isEmpty()) d.note(tr("Aucun appareil MIDI détecté. Branche un câble USB-MIDI (OTG) ou scanne en Bluetooth.",
            "No MIDI device found. Plug in a USB-MIDI cable (OTG) or scan for Bluetooth."))
        list.forEach { info ->
            val name = midi.nameOf(info)
            d.button(name.uppercase(), Tdr.CYAN) {
                midi.openInfo(info) { toast(tr("Impossible d'ouvrir ", "Cannot open ") + name) }
                d.dismiss()
            }
        }

        d.section("BLUETOOTH MIDI")
        val bleBox = LinearLayout(this)
        bleBox.orientation = LinearLayout.VERTICAL
        val found = HashSet<String>()
        val scanLabel = tr("▶ SCANNER (15 s)", "▶ SCAN (15 s)")
        val scanBtn = d.button(scanLabel, Tdr.YELLOW) {}
        d.body.addView(bleBox)
        val hint = d.note(tr("Allume l'instrument en mode Bluetooth puis lance le scan. SEQTRAK : ferme l'appli Yamaha, maintiens [ALL] + [SWING], puis scanne dans les 30 s.",
            "Put the instrument in Bluetooth mode, then scan. SEQTRAK: close the Yamaha app, hold [ALL] + [SWING], then scan within 30 s."))
        scanBtn.onClick {
            withBlePermission {
                val err = midi.startBleScan(
                    onFound = { dev, name ->
                        if (found.add(dev.address)) {
                            val b = TdrButton(this, "ᛒ  ${name.uppercase()}", Tdr.YELLOW).onClick {
                                toast(tr("Connexion à ", "Connecting to ") + name + "…")
                                midi.openBluetooth(dev, name) { toast(tr("Échec de la connexion Bluetooth", "Bluetooth connection failed")) }
                                d.dismiss()
                            }
                            b.latin = true
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(40))
                            lp.topMargin = dpi(4)
                            bleBox.addView(b, lp)
                        }
                    },
                    onEnd = {
                        scanBtn.text = scanLabel
                        scanBtn.active = false
                        if (found.isEmpty()) hint.text = tr("Rien trouvé. Vérifie que l'instrument est en mode Bluetooth.",
                            "Nothing found. Check that the instrument is in Bluetooth mode.")
                    }
                )
                if (err != null) {
                    toast(err)
                } else {
                    scanBtn.text = tr("SCAN EN COURS…", "SCANNING…")
                    scanBtn.active = true
                    hint.text = tr("Recherche… (♪MIDI = l'appareil s'annonce comme instrument MIDI)",
                        "Searching… (♪MIDI = the device advertises itself as MIDI)")
                }
            }
        }

        if (midi.isConnected) d.action(tr("DÉCONNECTER", "DISCONNECT"), Tdr.MAGENTA) { midi.close(); d.dismiss() }
        d.action(tr("FERMER", "CLOSE"), Tdr.DIM) { d.dismiss() }
        d.setOnDismissListener {
            midi.stopBleScan()
            hideSystemBars()
        }
        d.show()
    }

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBlePermission() =
        blePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun withBlePermission(action: () -> Unit) {
        if (hasBlePermission()) {
            action()
        } else {
            pendingBle = action
            requestPermissions(blePermissions(), REQ_BLE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE) {
            if (hasBlePermission()) pendingBle?.invoke()
            else toast(tr("Sans autorisation Bluetooth, le scan est impossible", "Bluetooth permission is required to scan"))
            pendingBle = null
        }
    }

    // ------------------------------------------------------------ cycle de vie
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != 0) showPage(0)
        else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        ctrl.save()
        Store.saveSeq(this, seq)
        Store.saveFx(this, fx)
    }

    override fun onDestroy() {
        seq.stop()
        fx.shutdown()
        midi.stopBleScan()
        midi.close()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
