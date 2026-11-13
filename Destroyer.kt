package fr.rktrl.app

import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Pad assignable du Destroy Mode. */
class PadConfig(var name: String, var channel: Int, var note: Int, var vel: Int = 110)

/**
 * Moteur du DESTROY MODE : effets MIDI live pilotés par un pad X/Y (esprit Kaoss Pad),
 * appliqués aux canaux MIDI choisis. Tourne sur son propre thread.
 *
 * Les effets agissent sur les notes du séquenceur et des pads, et/ou envoient des CC / pitch bend.
 */
class Destroyer(private val midi: MidiHub, private val seq: Sequencer) {

    companion object {
        const val REPEAT = 0; const val STUTTER = 1; const val ECHO = 2; const val FILTER = 3; const val PITCH = 4
        const val TAPE = 5; const val SCATTER = 6; const val OCTAVE = 7; const val WARP = 8
        const val COUNT = 9
        val names = arrayOf("REPEAT", "STUTTER", "ECHO", "FILTER", "PITCH", "TAPE STOP", "SCATTER", "OCTAVER", "WARP")

        private val repeatDivs = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 16.0)
        private val repeatLabels = arrayOf("1/2", "1/4", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/64")
        private val chopDivs = doubleArrayOf(1.0, 2.0, 4.0, 8.0)
        private val chopLabels = arrayOf("1/4", "1/8", "1/16", "1/32")
        private val echoBeats = doubleArrayOf(0.125, 0.25, 1.0 / 3.0, 0.5, 0.75, 1.0)
        private val echoLabels = arrayOf("1/32", "1/16", "1/8T", "1/8", "1/8.", "1/4")
        private val octShifts = intArrayOf(-24, -12, -7, -5, 0, 5, 7, 12, 24)
        private val warpSpeeds = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)

        fun pick(x: Float, n: Int) = (x * n).toInt().coerceIn(0, n - 1)
    }

    val armed = BooleanArray(COUNT).also { it[REPEAT] = true }
    /** MULTI : plusieurs effets à la fois ; sinon un seul effet est choisi. */
    @Volatile var multi = false
    @Volatile var mask = 0xFFFF
    @Volatile var x = 0.5f
    @Volatile var y = 0.5f
    @Volatile var touching = false
    /** Change à chaque relâchement : le séquenceur se recale alors sur le tempo normal. */
    @Volatile var generation = 0
    @Volatile private var restorePending = false
    @Volatile private var reqFilter = false
    @Volatile private var reqPitch = false
    /** Dernières valeurs CC74 / CC71 reçues de l'instrument (hors effet), pour revenir à l'origine. */
    private val seenCC = Array(17) { intArrayOf(-1, -1) }
    @Volatile private var engaged = false
    @Volatile private var engagedAt = 0L

    val pads = MutableList(8) { i -> PadConfig("PAD ${i + 1}", 1, 36 + i) }
    private val padSounding = arrayOfNulls<List<Int>>(8)
    private val padCh = IntArray(8)

    /** Valeur connue d'un CC (fournie par la page Controller), pour restaurer après FILTER. */
    var knownCC: ((Int, Int) -> Int?)? = null

    @Volatile private var alive = true
    private val lock = Any()

    private class Ev(val time: Long, val on: Boolean, val ch: Int, val note: Int, val vel: Int)
    private val events = ArrayList<Ev>()

    // REPEAT
    private val lastNotes = arrayOfNulls<List<Int>>(17)
    private val lastVel = IntArray(17) { 100 }
    private val captured = arrayOfNulls<List<Int>>(17)
    private val capVel = IntArray(17)
    private val repeatNext = LongArray(17)
    // FILTER / PITCH : canaux modifiés, à restaurer
    private val filterTouched = BooleanArray(17)
    private val filterOrigin = Array(17) { IntArray(2) }
    private val lastCC = Array(17) { intArrayOf(-1, -1) }
    private val pitchTouched = BooleanArray(17)
    private var lastBend = IntArray(17) { -1 }
    private var lastCcSend = 0L
    private var lastBendSend = 0L

    private val thread = Thread({ run() }, "rktrl-destroy").also { it.priority = Thread.MAX_PRIORITY - 1; it.start() }

    fun shutdown() {
        alive = false
        try { thread.join(200) } catch (_: InterruptedException) {}
        flushEvents()
        restoreAll()
    }

    private fun chOn(ch: Int) = ch in 1..16 && (mask shr (ch - 1)) and 1 == 1

    // Canaux réellement utilisés (pistes non muettes avec des notes + pads), recalculés régulièrement.
    // Les flux continus (FILTER, PITCH) ne partent que vers ces canaux : beaucoup moins de messages.
    @Volatile private var usedMask = 0xFFFF
    private var usedAt = 0L
    private fun refreshUsed(now: Long) {
        if (now - usedAt < 300_000_000L) return
        usedAt = now
        var m = 0
        for (t in seq.tracks) if (!t.mute && t.hasNotes()) m = m or (1 shl (t.channel - 1))
        for (p in pads) m = m or (1 shl (p.channel - 1))
        usedMask = if (m == 0) 0xFFFF else m
    }
    private fun streamOn(ch: Int) = chOn(ch) && (usedMask shr (ch - 1)) and 1 == 1
    fun isOn(e: Int, ch: Int) = engaged && armed[e] && chOn(ch)
    private fun anyOn(e: Int) = engaged && armed[e]

    private fun beatNs(): Double = 60_000_000_000.0 / seq.bpm

    // ------------------------------------------------------------ pad X/Y
    fun touchDown(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, 1f); y = ny.coerceIn(0f, 1f)
        touching = true
        if (!engaged) { restorePending = false; engaged = true; engagedAt = System.nanoTime(); resetEngage() }
    }

    fun touchMove(nx: Float, ny: Float) { x = nx.coerceIn(0f, 1f); y = ny.coerceIn(0f, 1f) }

    /** Doigt levé : tous les effets s'arrêtent et tout revient aux valeurs d'origine. */
    fun touchUp() {
        touching = false
        disengage()
    }

    /** CC reçu de l'instrument : sert à connaître la valeur d'origine du filtre. */
    fun observeCC(ch: Int, cc: Int, v: Int) {
        if (engaged || ch !in 1..16) return
        if (cc == 74) seenCC[ch][0] = v
        if (cc == 71) seenCC[ch][1] = v
    }

    fun setArmed(e: Int, on: Boolean) {
        armed[e] = on
        if (!on) when (e) {
            FILTER -> reqFilter = true
            PITCH -> reqPitch = true
            REPEAT -> synchronized(lock) { captured.fill(null) }
        }
    }

    fun isEngaged() = engaged

    private fun resetEngage() {
        synchronized(lock) {
            for (c in 1..16) { captured[c] = null; repeatNext[c] = 0L }
        }
    }

    private fun disengage() {
        if (!engaged) return
        engaged = false
        synchronized(lock) { captured.fill(null) }
        generation++
        restorePending = true      // la remise à zéro est faite par le thread des effets (pas de conflit d'envoi)
    }

    /** Coupe les échos en attente (on envoie les fins de notes, on oublie les notes à venir). */
    private fun flushEvents() {
        val offs: List<Ev>
        synchronized(lock) {
            offs = events.filter { !it.on }
            events.clear()
        }
        offs.forEach { midi.noteOff(it.ch, it.note) }
    }

    private fun restoreAll() { restoreFilter(); restorePitch() }

    private fun restoreFilter() {
        for (c in 1..16) if (filterTouched[c]) {
            midi.sendCC(c, 74, filterOrigin[c][0])
            midi.sendCC(c, 71, filterOrigin[c][1])
            filterTouched[c] = false
            lastCC[c][0] = -1; lastCC[c][1] = -1
        }
    }

    private fun restorePitch() {
        for (c in 1..16) if (pitchTouched[c]) {
            sendBend(c, 8192)
            pitchTouched[c] = false
            lastBend[c] = -1
        }
    }

    private fun sendBend(ch: Int, v: Int) {
        midi.pitchBend(ch, v)
    }

    // ------------------------------------------------------------ appelés par le séquenceur
    /** Facteur de vitesse (WARP × TAPE STOP) pour les pistes de ce canal. */
    fun speed(ch: Int): Double {
        if (!engaged || !chOn(ch)) return 1.0
        var f = 1.0
        if (armed[WARP]) f *= warpSpeeds[pick(x, warpSpeeds.size)]
        if (armed[TAPE]) f *= tapeFactor()
        return f
    }

    fun reverse(ch: Int): Boolean = isOn(WARP, ch) && y > 0.5f

    fun scatter(ch: Int, pos: Int, len: Int): Int {
        if (!isOn(SCATTER, ch) || len <= 1) return pos
        if (Random.nextFloat() >= y) return pos
        val r = 1 + (x * 15).toInt()
        return Math.floorMod(pos + Random.nextInt(-r, r + 1), len)
    }

    /** Transforme les notes d'un pas. Liste vide = la note est absorbée (REPEAT / STUTTER). */
    fun process(ch: Int, notesIn: List<Int>, vel: Int): List<Int> {
        if (!engaged || !chOn(ch)) { remember(ch, notesIn, vel); return notesIn }
        var notes = notesIn
        if (armed[OCTAVE]) {
            var shift = octShifts[pick(x, octShifts.size)]
            if (Random.nextFloat() < y * 0.6f) shift += if (Random.nextBoolean()) 12 else -12
            notes = notes.map { it + shift }.filter { it in 0..127 }
        }
        if (armed[STUTTER]) {
            val chop = beatNs() / chopDivs[pick(x, chopDivs.size)]
            val idx = ((System.nanoTime() - engagedAt) / chop).toLong()
            val h = ((idx * 2654435761L) ushr 7) % 100
            if (idx % 2L == 1L || h < (y * 100).toLong()) return emptyList()
        }
        if (armed[REPEAT]) {
            synchronized(lock) {
                if (captured[ch] == null) {
                    captured[ch] = lastNotes[ch] ?: notes
                    capVel[ch] = if (lastNotes[ch] != null) lastVel[ch] else vel
                }
            }
            return emptyList()
        }
        remember(ch, notes, vel)
        return notes
    }

    private fun remember(ch: Int, notes: List<Int>, vel: Int) {
        if (ch !in 1..16 || notes.isEmpty()) return
        synchronized(lock) { lastNotes[ch] = notes; lastVel[ch] = vel }
    }

    /** Après qu'une note a été jouée : ECHO (délai MIDI). */
    fun afterPlay(ch: Int, notes: List<Int>, vel: Int, gate: Long) {
        if (!isOn(ECHO, ch) || notes.isEmpty()) return
        val delay = (beatNs() * echoBeats[pick(x, echoBeats.size)]).toLong()
        val reps = 1 + (y * 7).roundToInt()
        val now = System.nanoTime()
        val g = min(gate, (delay * 0.85).toLong()).coerceAtLeast(5_000_000L)
        synchronized(lock) {
            if (events.size > 600) return
            var v = vel.toDouble()
            for (k in 1..reps) {
                v *= 0.72
                if (v < 6) break
                val t = now + delay * k
                notes.forEach { n ->
                    events.add(Ev(t, true, ch, n, v.toInt()))
                    events.add(Ev(t + g, false, ch, n, 0))
                }
            }
        }
    }

    private fun tapeFactor(): Double {
        val elapsed = (System.nanoTime() - engagedAt) / 1e9
        val dur = 2.0 - 1.85 * x                       // gauche = arrêt lent, droite = arrêt brutal
        val floor = 0.03 + 0.5 * y                     // bas = arrêt complet, haut = demi-vitesse
        return max(floor, 1.0 - (elapsed / dur) * (1.0 - floor))
    }

    // ------------------------------------------------------------ pads
    fun padDown(i: Int) {
        val pc = pads[i]
        val notes = process(pc.channel, listOf(pc.note.coerceIn(0, 127)), pc.vel)
        padCh[i] = pc.channel
        padSounding[i] = notes
        notes.forEach { midi.noteOn(pc.channel, it, pc.vel) }
        afterPlay(pc.channel, notes, pc.vel, 200_000_000L)
    }

    fun padUp(i: Int) {
        padSounding[i]?.forEach { midi.noteOff(padCh[i], it) }
        padSounding[i] = null
    }

    // ------------------------------------------------------------ thread
    private fun run() {
        while (alive) {
            val now = System.nanoTime()

            // relâchement : retour à l'origine (filtre, pitch), échos coupés
            if (restorePending && !engaged) {
                restorePending = false
                flushEvents()
                restoreAll()
            }
            if (reqFilter) { reqFilter = false; restoreFilter() }
            if (reqPitch) { reqPitch = false; restorePitch() }

            // événements programmés (ECHO, REPEAT)
            var due: List<Ev>? = null
            synchronized(lock) {
                if (events.isNotEmpty()) {
                    val d = events.filter { now >= it.time }
                    if (d.isNotEmpty()) { events.removeAll(d.toSet()); due = d }
                }
            }
            due?.forEach { if (it.on) midi.noteOn(it.ch, it.note, it.vel) else midi.noteOff(it.ch, it.note) }

            if (engaged) {
                refreshUsed(now)
                // REPEAT : rejoue en boucle les dernières notes captées
                if (armed[REPEAT]) {
                    val interval = (beatNs() / repeatDivs[pick(x, repeatDivs.size)]).toLong()
                        .coerceAtLeast(if (midi.isBle) 25_000_000L else 8_000_000L)
                    val lvl = 0.25 + 0.75 * y
                    for (c in 1..16) {
                        if (!chOn(c)) continue
                        val notes: List<Int>; val v: Int
                        synchronized(lock) {
                            if (captured[c] == null && lastNotes[c] != null) { captured[c] = lastNotes[c]; capVel[c] = lastVel[c] }
                            notes = captured[c] ?: emptyList(); v = capVel[c]
                        }
                        if (notes.isEmpty()) continue
                        if (now >= repeatNext[c]) {
                            val g = (interval * 0.5).toLong().coerceAtLeast(4_000_000L)
                            val vv = (v * lvl).toInt().coerceIn(1, 127)
                            notes.forEach { midi.noteOn(c, it, vv) }
                            synchronized(lock) { notes.forEach { events.add(Ev(now + g, false, c, it, 0)) } }
                            repeatNext[c] = if (repeatNext[c] == 0L || now - repeatNext[c] > interval) now + interval
                                            else repeatNext[c] + interval
                        }
                    }
                }

                // FILTER : X = cutoff (CC74), Y = résonance (CC71)
                if (anyOn(FILTER) && now - lastCcSend > 20_000_000L) {
                    val cut = (x * 127).roundToInt()
                    val res = (y * 127).roundToInt()
                    for (c in 1..16) {
                        if (!streamOn(c)) continue
                        if (!filterTouched[c]) {
                            filterTouched[c] = true
                            filterOrigin[c][0] = knownCC?.invoke(c, 74) ?: seenCC[c][0].takeIf { it >= 0 } ?: 64
                            filterOrigin[c][1] = knownCC?.invoke(c, 71) ?: seenCC[c][1].takeIf { it >= 0 } ?: 64
                        }
                        if (lastCC[c][0] != cut) { midi.sendCC(c, 74, cut); lastCC[c][0] = cut }
                        if (lastCC[c][1] != res) { midi.sendCC(c, 71, res); lastCC[c][1] = res }
                    }
                    lastCcSend = now
                }

                // PITCH : X = bend, Y = profondeur du vibrato
                if (anyOn(PITCH) && now - lastBendSend > 20_000_000L) {
                    val t = now / 1e9
                    val vib = sin(2 * PI * 6.0 * t) * y * 2400
                    val bend = ((8192 + (x - 0.5f) * 2 * 8191 + vib) / 32).roundToInt().times(32).coerceIn(0, 16383)
                    for (c in 1..16) {
                        if (!streamOn(c)) continue
                        pitchTouched[c] = true
                        if (lastBend[c] != bend) { sendBend(c, bend); lastBend[c] = bend }
                    }
                    lastBendSend = now
                }
            }
            LockSupport.parkNanos(if (engaged || due != null) 500_000L else 2_000_000L)
        }
    }

    // ------------------------------------------------------------ affichage
    /** Description de ce que font X et Y pour un effet, avec les valeurs actuelles. */
    fun describe(e: Int): String {
        val xs = x; val ys = y
        return when (e) {
            REPEAT -> "X ${repeatLabels[pick(xs, repeatLabels.size)]} · Y ${(25 + 75 * ys).roundToInt()}%"
            STUTTER -> "X ${chopLabels[pick(xs, chopLabels.size)]} · Y ${(ys * 100).roundToInt()}%"
            ECHO -> "X ${echoLabels[pick(xs, echoLabels.size)]} · Y ×${1 + (ys * 7).roundToInt()}"
            FILTER -> "X CC74 ${(xs * 127).roundToInt()} · Y CC71 ${(ys * 127).roundToInt()}"
            PITCH -> "X BEND ${((xs - 0.5f) * 200).roundToInt()} · Y VIB ${(ys * 100).roundToInt()}%"
            TAPE -> "X ${"%.1f".format(2.0 - 1.85 * xs)}s · Y ${((0.03 + 0.5 * ys) * 100).roundToInt()}%"
            SCATTER -> "X ±${1 + (xs * 15).toInt()} · Y ${(ys * 100).roundToInt()}%"
            OCTAVE -> "X ${octShifts[pick(xs, octShifts.size)].let { if (it > 0) "+$it" else "$it" }} · Y ${(ys * 60).roundToInt()}%"
            else -> "X ×${warpSpeeds[pick(xs, warpSpeeds.size)].let { if (it < 1) "%.2f".format(it) else "%.0f".format(it) }} · Y " +
                    (if (ys > 0.5f) "◄" else "►")
        }
    }
}
