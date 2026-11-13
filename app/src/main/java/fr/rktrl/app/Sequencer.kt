package fr.rktrl.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

const val TRACKS = 16
const val MAX_STEPS = 1024
const val PAGE_STEPS = 32

class Step(
    @Volatile var on: Boolean = false,
    @Volatile var note: Int = 60,
    @Volatile var vel: Int = 100,
    @Volatile var dur: Int = Durs.DEFAULT,   // index dans Durs
    @Volatile var chord: Int = 0,            // index dans Chords
    @Volatile var cond: Int = 0,             // index dans Conds (0 = toujours)
    @Volatile var ratch: Int = 1,            // nombre de répétitions dans le pas (1–8)
    // --- expression (mode avancé, appui long sur un pas) ; -1 = non utilisé
    @Volatile var pan: Int = -1,             // CC10, 0–127
    @Volatile var mod: Int = -1,             // CC1, 0–127
    @Volatile var bend: Int = -1,            // pitch bend 0–16383 (8192 = centre)
    @Volatile var accent: Boolean = false,   // vélocité poussée au maximum
    @Volatile var slide: Boolean = false     // liée à la note suivante (legato + portamento)
) {
    fun reset() {
        on = false; note = 60; vel = 100; dur = Durs.DEFAULT; chord = 0; cond = 0; ratch = 1
        pan = -1; mod = -1; bend = -1; accent = false; slide = false
    }
    fun hasExpression() = pan >= 0 || mod >= 0 || bend >= 0 || accent || slide
    fun isDefault() = !on && note == 60 && vel == 100 && dur == Durs.DEFAULT && chord == 0 && cond == 0 && ratch == 1 &&
        !hasExpression()
    fun copyFrom(o: Step) {
        on = o.on; note = o.note; vel = o.vel; dur = o.dur; chord = o.chord; cond = o.cond; ratch = o.ratch
        pan = o.pan; mod = o.mod; bend = o.bend; accent = o.accent; slide = o.slide
    }
}

class SeqTrack(index: Int) {
    /** Deux pages (A et B) pour créer des variations à la volée. */
    val patterns = Array(2) { Array(MAX_STEPS) { Step() } }
    @Volatile var page = 0
    val steps: Array<Step> get() = patterns[page]

    @Volatile var name = ""
    @Volatile var channel = index + 1
    @Volatile var length = 16
    @Volatile var div = 2              // index dans Divs
    @Volatile var transpose = 0
    @Volatile var mute = false
    @Volatile var dir = 0              // index dans Dirs
    /** Premier pas de la boucle (la fin est donnée par length). */
    @Volatile var start = 0
    /** SOLO : seul ce pas joue (-1 = désactivé). */
    @Volatile var solo = -1
    @Volatile var pos = -1
    @Volatile var releaseRequest = false
    internal var loop = 0
    internal var counter = 0
    internal var ping = 1
    // notes tenues (durée ∞) — utilisées uniquement par le thread du séquenceur
    internal var held: List<Int> = emptyList()
    internal var heldCh = 1
    // expression envoyée au pas précédent (pour revenir au neutre ensuite)
    internal var bendSent = false
    internal var slideSent = false

    fun hasNotes(): Boolean {
        val s = steps
        for (i in start.coerceIn(0, length - 1) until length) if (s[i].on) return true
        return false
    }

    fun label(i: Int): String = if (name.isBlank()) "%02d".format(i + 1) else name
}

object Divs {
    val labels = arrayOf("1/32", "1/16T", "1/16", "1/8T", "1/8", "1/4")
    val perBeat = doubleArrayOf(8.0, 6.0, 4.0, 3.0, 2.0, 1.0)
    const val D16 = 2
    const val D8 = 4
    const val D4 = 5
}

object Dirs {
    fun labels() = arrayOf("►", "◄", "►◄", tr("HASARD", "RANDOM"))
}

/**
 * Durée d'une note, en % d'un pas. Au-delà de 100 % la note couvre plusieurs pas.
 * INF (∞) = la note est tenue jusqu'à la prochaine note de la piste (ou STOP / MUTE / KILL).
 */
object Durs {
    const val INF = -1
    val pct = intArrayOf(10, 25, 50, 75, 100, 150, 200, 300, 400, 500, 600, 700, 800,
        1000, 1200, 1600, 2000, 2400, 2800, 3200, 4800, 6400, INF)
    const val DEFAULT = 2                 // 50 %
    val LAST = pct.size - 1

    fun isInf(i: Int) = pct[i.coerceIn(0, LAST)] == INF

    fun nearest(percent: Int): Int {
        var best = 0
        for (i in 0 until LAST) if (kotlin.math.abs(pct[i] - percent) < kotlin.math.abs(pct[best] - percent)) best = i
        return best
    }

    fun label(i: Int): String {
        val p = pct[i.coerceIn(0, LAST)]
        return when {
            p == INF -> "∞"
            p < 100 -> "$p%"
            p % 100 == 0 -> "${p / 100}"
            else -> "${p / 100}½"
        }
    }

    fun extraSteps(i: Int): Int {
        val p = pct[i.coerceIn(0, LAST)]
        return if (p == INF) 9999 else ((p + 99) / 100 - 1).coerceAtLeast(0)
    }
}

object Chords {
    val names = arrayOf("—", "5", "OCT", "PWR", "MAJ", "MIN", "SUS2", "SUS4", "MAJ7", "m7", "7", "m9", "ADD9", "DIM", "♭5")
    val intervals = arrayOf(
        intArrayOf(0), intArrayOf(0, 7), intArrayOf(0, 12), intArrayOf(0, 7, 12), intArrayOf(0, 4, 7), intArrayOf(0, 3, 7),
        intArrayOf(0, 2, 7), intArrayOf(0, 5, 7), intArrayOf(0, 4, 7, 11), intArrayOf(0, 3, 7, 10),
        intArrayOf(0, 4, 7, 10), intArrayOf(0, 3, 7, 10, 14), intArrayOf(0, 4, 7, 14), intArrayOf(0, 3, 6), intArrayOf(0, 6)
    )
    const val NONE = 0; const val FIFTH = 1; const val OCT = 2; const val POWER = 3; const val MAJ = 4; const val MIN = 5
    const val SUS2 = 6; const val SUS4 = 7; const val MAJ7 = 8; const val MIN7 = 9; const val DOM7 = 10
    const val MIN9 = 11; const val ADD9 = 12; const val DIM = 13; const val TRITONE = 14
    val LAST = names.size - 1

    fun notes(root: Int, chord: Int): List<Int> =
        intervals[chord.coerceIn(0, LAST)].map { root + it }.filter { it in 0..127 }
}

/** Conditions de déclenchement (esprit Elektron) : probabilité ou cycle de boucles. */
object Conds {
    val labels = arrayOf("100%", "75%", "50%", "25%", "10%", "1:2", "2:2", "1:3", "2:3", "3:3",
        "1:4", "2:4", "3:4", "4:4", "1ST", "!1ST")
    val LAST = labels.size - 1

    /** loop = numéro de boucle de la piste (0 = premier passage). */
    fun pass(c: Int, loop: Int): Boolean = when (c) {
        0 -> true
        1 -> Random.nextInt(100) < 75
        2 -> Random.nextInt(100) < 50
        3 -> Random.nextInt(100) < 25
        4 -> Random.nextInt(100) < 10
        5 -> loop % 2 == 0
        6 -> loop % 2 == 1
        7 -> loop % 3 == 0
        8 -> loop % 3 == 1
        9 -> loop % 3 == 2
        10 -> loop % 4 == 0
        11 -> loop % 4 == 1
        12 -> loop % 4 == 2
        13 -> loop % 4 == 3
        14 -> loop == 0
        else -> loop != 0
    }
}

object Scales {
    val names = arrayOf(
        "MAJEUR|MAJOR", "MINEUR|MINOR", "MIN. HARMO|HARM. MIN", "DORIEN|DORIAN", "PHRYGIEN|PHRYGIAN", "LYDIEN|LYDIAN",
        "MIXOLYDIEN|MIXOLYDIAN", "PENTA MAJ", "PENTA MIN", "BLUES", "PAR TONS|WHOLE TONE", "CHROMATIQUE|CHROMATIC"
    )
    fun name(i: Int): String { val p = names[i].split("|"); return if (p.size > 1) tr(p[0], p[1]) else p[0] }
    val intervals = arrayOf(
        intArrayOf(0, 2, 4, 5, 7, 9, 11),
        intArrayOf(0, 2, 3, 5, 7, 8, 10),
        intArrayOf(0, 2, 3, 5, 7, 8, 11),
        intArrayOf(0, 2, 3, 5, 7, 9, 10),
        intArrayOf(0, 1, 3, 5, 7, 8, 10),
        intArrayOf(0, 2, 4, 6, 7, 9, 11),
        intArrayOf(0, 2, 4, 5, 7, 9, 10),
        intArrayOf(0, 2, 4, 7, 9),
        intArrayOf(0, 3, 5, 7, 10),
        intArrayOf(0, 3, 5, 6, 7, 10),
        intArrayOf(0, 2, 4, 6, 8, 10),
        IntArray(12) { it }
    )
}

/** Copie d'une piste (presse-papiers COPIER / COLLER). */
class TrackClip(src: SeqTrack) {
    val steps = Array(MAX_STEPS) { Step().also { s -> s.copyFrom(src.steps[it]) } }
    val length = src.length
    val div = src.div
    val transpose = src.transpose
    val dir = src.dir
    val start = src.start
    val from = src
}

/**
 * Moteur de séquence : 16 pistes x 1024 pas (pages A/B), horloge interne sur un thread dédié,
 * division / longueur / sens de lecture par piste, swing, notes longues / tenues, accords,
 * conditions, ratchets, horloge MIDI, effets du Destroy Mode.
 */
class Sequencer(private val midi: MidiHub) {

    val tracks = Array(TRACKS) { SeqTrack(it) }
    @Volatile var bpm = 120
    @Volatile var swing = 50
    @Volatile var running = false
        private set
    /** Effets live (Destroy Mode). */
    @Volatile var fx: Destroyer? = null

    var clip: TrackClip? = null

    var onStep: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var clockOn = false

    private class Off(val time: Long, val ch: Int, val note: Int)
    private class On(val time: Long, val ch: Int, val notes: List<Int>, val vel: Int, val gate: Long)
    private val offs = ArrayList<Off>()
    private val ons = ArrayList<On>()

    fun toggle() { if (running) stop() else start() }

    fun start() {
        if (running) return
        tracks.forEach { it.pos = -1; it.loop = 0; it.counter = 0; it.ping = 1 }
        running = true
        clockOn = Settings.midiClock
        if (clockOn) midi.realtime(0xFA)
        val t = Thread({ loop() }, "rktrl-seq")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
        main.post { onStep?.invoke() }
    }

    fun stop() {
        if (!running) return
        running = false
        try { thread?.join(300) } catch (_: InterruptedException) {}
        thread = null
        synchronized(offs) {
            offs.forEach { midi.noteOff(it.ch, it.note) }
            offs.clear()
            ons.clear()
        }
        tracks.forEach { release(it) }
        tracks.forEach { t ->
            if (t.bendSent) { midi.pitchBend(t.channel, 8192); t.bendSent = false }
            if (t.slideSent) { midi.sendCC(t.channel, 65, 0); t.slideSent = false }
        }
        if (clockOn) midi.realtime(0xFC)
        tracks.forEach { it.pos = -1 }
        main.post { onStep?.invoke() }
    }

    private fun release(tr: SeqTrack) {
        if (tr.held.isEmpty()) return
        tr.held.forEach { midi.noteOff(tr.heldCh, it) }
        tr.held = emptyList()
    }

    /** Joue une note ; si elle sonne déjà sur ce canal, on la coupe d'abord (pas de note coincée). */
    private fun trigger(ch: Int, note: Int, vel: Int) {
        var wasOn = false
        synchronized(offs) {
            val it = offs.iterator()
            while (it.hasNext()) {
                val o = it.next()
                if (o.ch == ch && o.note == note) { it.remove(); wasOn = true }
            }
        }
        if (wasOn) midi.noteOff(ch, note)
        midi.noteOn(ch, note, vel)
    }

    private fun playTimed(ch: Int, notes: List<Int>, vel: Int, at: Long, gate: Long) {
        notes.forEach { n ->
            trigger(ch, n, vel)
            synchronized(offs) { offs.add(Off(at + gate, ch, n)) }
        }
        fx?.afterPlay(ch, notes, vel, gate)
    }

    /** Avance la tête de lecture selon le sens choisi. */
    /** Avance la tête de lecture selon le sens choisi, entre le début (start) et la fin (length − 1). */
    private fun advance(tr: SeqTrack, reverse: Boolean) {
        val end = tr.length - 1
        val st = tr.start.coerceIn(0, end)
        val span = end - st + 1
        var d = tr.dir
        if (reverse) d = when (d) { 0 -> 1; 1 -> 0; else -> d }
        if (tr.pos > end || (tr.pos >= 0 && tr.pos < st)) tr.pos = -1
        when (d) {
            1 -> { // arrière
                val p = if (tr.pos < 0) end else tr.pos - 1
                if (p < st) { tr.pos = end; tr.loop++ } else tr.pos = p
            }
            2 -> { // aller-retour
                if (tr.pos < 0) { tr.pos = st; tr.ping = 1 }
                else if (span <= 1) { tr.pos = st; tr.loop++ }
                else {
                    var p = tr.pos + tr.ping
                    if (p > end) { tr.ping = -1; p = end - 1 }
                    if (p < st) { tr.ping = 1; p = st + 1; tr.loop++ }
                    tr.pos = p.coerceIn(st, end)
                }
            }
            3 -> { // hasard
                tr.pos = st + Random.nextInt(span)
                tr.counter++
                if (tr.counter % span == 0) tr.loop++
            }
            else -> { // avant
                val p = tr.pos + 1
                if (tr.pos < 0) tr.pos = st
                else if (p > end) { tr.pos = st; tr.loop++ } else tr.pos = p
            }
        }
    }


    private fun loop() {
        val t0 = System.nanoTime() + 5_000_000L
        val next = LongArray(TRACKS) { t0 }
        val count = LongArray(TRACKS)
        var nextClock = t0
        var fxGen = fx?.generation ?: 0

        while (running) {
            val now = System.nanoTime()
            val beat = 60_000_000_000.0 / bpm
            val fxx = fx

            // fin d'un effet (TAPE STOP, WARP…) : les pas ralentis sont recalés tout de suite
            val gen = fxx?.generation ?: 0
            if (gen != fxGen) {
                fxGen = gen
                if (fxx?.isEngaged() != true) for (ti in 0 until TRACKS) {
                    // seulement les pistes dont le pas en cours a été étiré par l'effet
                    val normal = beat / Divs.perBeat[tracks[ti].div.coerceIn(0, Divs.perBeat.size - 1)] *
                        2 * maxOf(swing, 100 - swing) / 100.0
                    if (next[ti] - now > normal) next[ti] = now
                }
            }

            // 1) fins de notes + notes de ratchet en attente
            synchronized(offs) {
                val it = offs.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    if (now >= o.time) { midi.noteOff(o.ch, o.note); it.remove() }
                }
            }
            var due: List<On>? = null
            synchronized(offs) {
                if (ons.isNotEmpty()) {
                    val d = ons.filter { now >= it.time }
                    if (d.isNotEmpty()) { ons.removeAll(d.toSet()); due = d }
                }
            }
            due?.forEach { playTimed(it.ch, it.notes, it.vel, it.time, it.gate) }

            // 2) pas de chaque piste
            var changed = false
            for (ti in 0 until TRACKS) {
                val tr = tracks[ti]
                if (tr.releaseRequest || tr.mute) { release(tr); tr.releaseRequest = false }
                if (now < next[ti]) continue

                val ch = tr.channel
                val speed = fxx?.speed(ch) ?: 1.0
                val stepDur = beat / Divs.perBeat[tr.div.coerceIn(0, Divs.perBeat.size - 1)] / speed
                advance(tr, fxx?.reverse(ch) == true)
                val sw = swing / 100.0
                val thisDur = if (count[ti] % 2L == 0L) stepDur * 2 * sw else stepDur * 2 * (1 - sw)
                val idx = (fxx?.scatter(ch, tr.pos, tr.length) ?: tr.pos).coerceIn(0, MAX_STEPS - 1)
                val st = tr.steps[idx]

                val soloOk = tr.solo < 0 || idx == tr.solo
                if (st.on && !tr.mute && soloOk && Conds.pass(st.cond, tr.loop)) {
                    var notes = Chords.notes((st.note + tr.transpose).coerceIn(0, 127), st.chord)
                    val vel = if (st.accent) 127 else st.vel
                    // expression du pas : envoyée juste avant la note
                    if (st.pan >= 0) midi.sendCC(ch, 10, st.pan)
                    if (st.mod >= 0) midi.sendCC(ch, 1, st.mod)
                    if (st.bend >= 0) { midi.pitchBend(ch, st.bend); tr.bendSent = true }
                    else if (tr.bendSent) { midi.pitchBend(ch, 8192); tr.bendSent = false }
                    if (st.slide && !tr.slideSent) { midi.sendCC(ch, 65, 127); tr.slideSent = true }
                    else if (!st.slide && tr.slideSent) { midi.sendCC(ch, 65, 0); tr.slideSent = false }
                    notes = fxx?.process(ch, notes, vel) ?: notes
                    if (notes.isEmpty()) {
                        // effet (repeat / stutter) : la note est absorbée
                    } else if (Durs.isInf(st.dur)) {
                        if (!(tr.held == notes && tr.heldCh == ch)) {
                            release(tr)
                            notes.forEach { trigger(ch, it, vel) }
                            tr.held = notes
                            tr.heldCh = ch
                        }
                    } else {
                        release(tr)
                        val pc = Durs.pct[st.dur.coerceIn(0, Durs.LAST)]
                        val r = st.ratch.coerceIn(1, 8)
                        val slot = thisDur / r
                        if (r == 1) {
                            var g = (if (pc <= 100) thisDur * pc / 100.0 else stepDur * pc / 100.0)
                                .toLong().coerceAtLeast(5_000_000L)
                            // SLIDE : la note déborde sur la suivante (legato → glissé sur un synthé mono)
                            if (st.slide) g = maxOf(g, (stepDur * 1.15).toLong())
                            playTimed(ch, notes, vel, next[ti], g)
                        } else {
                            // ratchet : r notes réparties dans le pas
                            val g = (slot * minOf(pc, 90) / 100.0).toLong().coerceAtLeast(4_000_000L)
                            playTimed(ch, notes, vel, next[ti], g)
                            synchronized(offs) {
                                for (k in 1 until r) ons.add(On(next[ti] + (slot * k).toLong(), ch, notes, vel, g))
                            }
                        }
                    }
                } else if (tr.held.isNotEmpty()) {
                    var stillInf = false
                    val s = tr.steps
                    for (i in 0 until tr.length) {
                        if (s[i].on && Durs.isInf(s[i].dur)) { stillInf = true; break }
                    }
                    if (!stillInf) release(tr)
                }

                next[ti] += thisDur.toLong()
                if (next[ti] < now) next[ti] = now
                count[ti]++
                changed = true
            }

            // 3) horloge MIDI (24 ppqn) — tempo de base, hors effets
            if (clockOn) {
                if (nextClock < now - beat.toLong()) nextClock = now
                if (now >= nextClock) {
                    midi.realtime(0xF8)
                    nextClock += (beat / 24.0).toLong()
                }
            }

            if (changed) main.post { onStep?.invoke() }
            LockSupport.parkNanos(250_000L)
        }
    }

    // ------------------------------------------------------------ outils d'édition
    fun randomize(
        track: SeqTrack, root: Int, octave: Int, scale: Int, rangeOct: Int,
        density: Int, randomVel: Boolean, randomDur: Boolean
    ) {
        val base = (octave + 1) * 12 + root
        val pool = ArrayList<Int>()
        for (o in 0 until rangeOct) for (iv in Scales.intervals[scale]) pool.add(base + o * 12 + iv)
        pool.add(base + rangeOct * 12)
        val notes = pool.filter { it in 0..127 }.ifEmpty { listOf(base.coerceIn(0, 127)) }
        val durs = intArrayOf(1, 2, 2, 3, 4, 5, 6)
        val st = track.steps
        for (i in 0 until MAX_STEPS) {
            val s = st[i]
            if (i >= track.length) { s.reset(); continue }
            s.reset()
            s.on = Random.nextInt(100) < density
            s.note = notes.random()
            s.vel = if (randomVel) Random.nextInt(64, 128) else 100
            s.dur = if (randomDur) durs.random() else Durs.DEFAULT
        }
        st[0].on = true
        st[0].note = base.coerceIn(0, 127)
    }

    /** KILL : efface la page en cours (A ou B) de la piste et coupe ses notes tenues. */
    fun kill(track: SeqTrack) {
        for (s in track.steps) s.reset()
        track.solo = -1
        track.releaseRequest = true
        if (!running) release(track)
    }

    fun copy(track: SeqTrack) { clip = TrackClip(track) }

    fun paste(track: SeqTrack): Boolean {
        val c = clip ?: return false
        val dst = track.steps
        for (i in 0 until MAX_STEPS) dst[i].copyFrom(c.steps[i])
        track.length = c.length
        track.div = c.div
        track.transpose = c.transpose
        track.dir = c.dir
        track.start = c.start.coerceIn(0, track.length - 1)
        track.solo = -1
        track.releaseRequest = true
        return true
    }

    /**
     * Séquenceur euclidien (esprit Digitakt) : répartit au mieux `pulses` notes sur `steps` pas,
     * décalées de `rot`. Une 2e couche peut être combinée (OU, XOR, ET, MOINS).
     */
    fun euclid(
        track: SeqTrack, steps: Int, pulses: Int, rot: Int,
        pulses2: Int, rot2: Int, mode: Int, note: Int, keepNotes: Boolean
    ) {
        val n = steps.coerceIn(1, 64)
        fun layer(k: Int, r: Int): BooleanArray {
            val out = BooleanArray(n)
            if (k <= 0) return out
            val kk = k.coerceAtMost(n)
            for (i in 0 until n) {
                val j = Math.floorMod(i - r, n)
                out[i] = (j * kk) % n < kk
            }
            return out
        }
        val a = layer(pulses, rot)
        val b = layer(pulses2, rot2)
        val st = track.steps
        for (i in 0 until MAX_STEPS) {
            val s = st[i]
            if (i >= n) { s.reset(); continue }
            val hit = when (mode) {
                1 -> a[i] || b[i]
                2 -> a[i] xor b[i]
                3 -> a[i] && b[i]
                4 -> a[i] && !b[i]
                else -> a[i]
            }
            val had = s.on
            if (hit) {
                if (!(keepNotes && had)) { s.note = note.coerceIn(0, 127); s.vel = 100; s.dur = Durs.DEFAULT; s.chord = 0 }
                s.on = true
            } else {
                s.on = false
            }
        }
        track.length = n
        track.start = 0
        track.solo = -1
        track.releaseRequest = true
    }
}
