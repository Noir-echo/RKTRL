package fr.rktrl.app

import kotlin.random.Random

/**
 * AUTO : génération de séquences « style » à la volée.
 * Chaque type combine plusieurs modèles rythmiques/mélodiques et une part de hasard,
 * donc chaque appui sur GÉNÉRER donne une variante différente.
 */
object AutoGen {

    val types = arrayOf("BASSLINE", "LEAD", "ACID", "PAD", "DRONE", "ARP", "TECHNO", "BERLIN",
        "RIFF", "DJENT", "EBM", "SOLO")
    /** Octave conseillée pour chaque type (C2 = 36…). */
    val defaultOctave = intArrayOf(2, 4, 2, 3, 2, 4, 1, 3, 2, 1, 2, 4)

    private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val PHRYG = intArrayOf(0, 1, 3, 5, 7, 8, 10)

    // durées (index Durs)
    private const val D25 = 1
    private const val D50 = 2
    private const val D75 = 3
    private const val D100 = 4
    private const val D150 = 5   // chevauche le pas suivant → slide / legato
    private const val D200 = 6
    private const val D300 = 7
    private const val D400 = 8
    private const val D800 = 12
    private val DINF = Durs.LAST

    private class Ctx(val root: Int, val octave: Int, val minor: Boolean) {
        val scale = if (minor) MINOR else MAJOR
        val base get() = (octave + 1) * 12 + root
        /** Note du degré d (0 = fondamentale), avec débordement d'octave. */
        fun deg(d: Int, oct: Int = 0): Int {
            val n = scale.size
            val o = Math.floorDiv(d, n)
            val i = Math.floorMod(d, n)
            return (base + (o + oct) * 12 + scale[i]).coerceIn(0, 127)
        }
        /** Qualité d'accord diatonique pour le degré d (enrichie). */
        fun chordOn(d: Int): Int {
            val i = Math.floorMod(d, 7)
            return if (minor) when (i) {
                0 -> listOf(Chords.MIN7, Chords.MIN9, Chords.MIN).random()
                1 -> Chords.DIM
                2 -> listOf(Chords.MAJ7, Chords.ADD9).random()
                3 -> listOf(Chords.MIN7, Chords.MIN).random()
                4 -> listOf(Chords.MIN7, Chords.SUS4).random()
                5 -> listOf(Chords.MAJ7, Chords.ADD9).random()
                else -> listOf(Chords.DOM7, Chords.MAJ, Chords.SUS2).random()
            } else when (i) {
                0 -> listOf(Chords.MAJ7, Chords.ADD9, Chords.MAJ).random()
                1 -> listOf(Chords.MIN7, Chords.MIN9).random()
                2 -> Chords.MIN7
                3 -> listOf(Chords.MAJ7, Chords.ADD9).random()
                4 -> listOf(Chords.DOM7, Chords.SUS4).random()
                5 -> listOf(Chords.MIN7, Chords.MIN9).random()
                else -> Chords.DIM
            }
        }
    }

    fun generate(tr: SeqTrack, type: Int, root: Int, octave: Int, minor: Boolean) {
        val c = Ctx(root, octave, minor)
        for (s in tr.steps) s.reset()
        tr.transpose = 0
        when (type) {
            0 -> bassline(tr, c)
            1 -> lead(tr, c)
            2 -> acid(tr, c)
            3 -> pad(tr, c)
            4 -> drone(tr, c)
            5 -> arp(tr, c)
            6 -> techno(tr, c)
            7 -> berlin(tr, c)
            8 -> riff(tr, c)
            9 -> djent(tr, c)
            10 -> ebm(tr, c)
            else -> solo(tr, c)
        }
        tr.dir = 0
        tr.start = 0
        tr.solo = -1
        tr.releaseRequest = true
    }

    private fun set(tr: SeqTrack, i: Int, note: Int, vel: Int = 100, dur: Int = D50, chord: Int = 0, ratch: Int = 1) {
        if (i !in 0 until MAX_STEPS) return
        val s = tr.steps[i]
        s.on = true; s.note = note.coerceIn(0, 127); s.vel = vel.coerceIn(1, 127); s.dur = dur; s.chord = chord
        s.ratch = ratch
    }

    private fun pattern(p: String, i: Int) = p[i % p.length] == 'x'

    private val progressions = arrayOf(
        intArrayOf(0, 5, 2, 6), intArrayOf(0, 3, 5, 4), intArrayOf(0, 6, 5, 6),
        intArrayOf(0, 3, 0, 4), intArrayOf(0, 5, 3, 4), intArrayOf(0, 2, 5, 6)
    )

    // ------------------------------------------------------------ BASSLINE (house / disco / funk)
    private fun bassline(tr: SeqTrack, c: Ctx) {
        tr.length = 32; tr.div = Divs.D16
        val bar2 = listOf(3, 4, 5, 6, -2).random()          // degré de la 2e mesure
        when (Random.nextInt(3)) {
            0 -> { // disco : octaves sur les croches
                for (i in 0 until 32 step 2) {
                    val d = if (i < 16) 0 else bar2
                    val up = (i / 2) % 2 == 1
                    set(tr, i, c.deg(d, if (up) 1 else 0), if (up) 92 else 112, D50)
                }
                if (Random.nextBoolean()) set(tr, 15, c.deg(bar2 - 1), 90, D25)
            }
            1 -> { // funk syncopée
                val rhythm = listOf("x..x..x.x.x..x..", "x.xx..x..x.x.x..", "x..x.x..x..xx.x.").random()
                val pool = intArrayOf(0, 0, 0, 4, 6, 7, 2, 3)
                for (i in 0 until 32) if (pattern(rhythm, i)) {
                    val shift = if (i < 16) 0 else bar2
                    val d = if (i % 16 == 0) 0 else pool.random()
                    set(tr, i, c.deg(d + shift), if (i % 4 == 0) 115 else Random.nextInt(80, 105),
                        listOf(D25, D50, D50, D75).random())
                }
            }
            else -> { // house : contretemps
                for (i in 0 until 32) {
                    val shift = if (i < 16) 0 else bar2
                    if (i % 4 == 2) set(tr, i, c.deg(shift), 110, D75)
                    else if (i % 4 == 3 && Random.nextInt(4) == 0) set(tr, i, c.deg(shift, 1), 85, D25)
                }
            }
        }
    }

    // ------------------------------------------------------------ LEAD (phrase question / réponse)
    private fun lead(tr: SeqTrack, c: Ctx) {
        tr.length = 32; tr.div = Divs.D16
        val penta = intArrayOf(0, 2, 3, 4, 6)                   // degrés « pentatoniques » de la gamme
        var idx = Random.nextInt(penta.size)
        var oct = 0
        val phrase = ArrayList<Pair<Int, Int>>()                 // (position, degré)
        var i = 0
        while (i < 16) {
            val d = penta[idx] + oct * 7
            phrase.add(i to d)
            // déplacement mélodique, surtout par petits intervalles
            val mv = listOf(-1, -1, 1, 1, 1, 2, -2, 0).random()
            idx += mv
            if (idx >= penta.size) { idx = 0; oct = (oct + 1).coerceAtMost(1) }
            if (idx < 0) { idx = penta.size - 1; oct = (oct - 1).coerceAtLeast(-1) }
            i += listOf(1, 2, 2, 2, 3, 4).random()
        }
        // mesure 1 : question ; mesure 2 : reprise du début + fin sur la fondamentale
        phrase.forEachIndexed { k, (pos, d) ->
            val nextPos = phrase.getOrNull(k + 1)?.first ?: 16
            val gap = nextPos - pos
            val dur = when { gap >= 4 -> D300; gap >= 2 -> D150; else -> D75 }
            set(tr, pos, c.deg(d, 0), Random.nextInt(90, 120), dur)
            if (pos < 10) set(tr, pos + 16, c.deg(d, 0), Random.nextInt(85, 115), dur)
        }
        set(tr, 28, c.deg(0), 110, D400)
        for (k in 29 until 32) tr.steps[k].on = false
    }

    // ------------------------------------------------------------ ACID (esprit 303 : accents + slides)
    private fun acid(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D16
        val acidScale = PHRYG
        val pool = intArrayOf(0, 0, 0, 12, 12, 3, 7, 10, 1, 5, -2)
        val accentP = listOf("x...x.x.....x..x", "x..x..x...x.x...", "x.....x.x...x.x.").random()
        for (i in 0 until 16) {
            if (i != 0 && Random.nextInt(100) < 22) continue     // silences
            val semi = if (i == 0) 0 else pool.random()
            // on reste dans la couleur phrygienne/mineure
            val n = c.base + semi
            val ok = acidScale.contains(Math.floorMod(semi, 12)) || semi == 12
            val note = if (ok) n else c.base
            val accent = pattern(accentP, i)
            val slide = Random.nextInt(100) < 28
            set(tr, i, note, if (accent) 127 else 78, if (slide) D150 else listOf(D25, D50, D50).random())
        }
        // dernière note : souvent un slide vers la fondamentale
        if (Random.nextBoolean()) set(tr, 15, c.base + 12, 100, D150)
    }

    // ------------------------------------------------------------ PAD (accords longs et mouvants)
    private fun pad(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D4            // 16 noires = 4 mesures
        val prog = progressions.random()
        for (bar in 0 until 4) {
            val d = prog[bar]
            val pos = bar * 4
            val chordRoot = c.deg(d)
            if (Random.nextInt(3) == 0) {
                // l'accord « bouge » : suspension puis résolution
                set(tr, pos, chordRoot, 85, D200, Chords.SUS4)
                set(tr, pos + 2, chordRoot, 80, D200, c.chordOn(d))
            } else {
                set(tr, pos, chordRoot, Random.nextInt(75, 95), D400, c.chordOn(d))
            }
        }
    }

    // ------------------------------------------------------------ DRONE (une note continue)
    private fun drone(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D4
        set(tr, 0, c.base, 100, DINF, listOf(Chords.NONE, Chords.NONE, Chords.FIFTH, Chords.OCT).random())
    }

    // ------------------------------------------------------------ ARP (arpège sur une grille d'accords)
    private fun arp(tr: SeqTrack, c: Ctx) {
        tr.length = 32; tr.div = Divs.D16
        val prog = progressions.random()
        val shape = listOf(intArrayOf(0, 2, 4, 7), intArrayOf(0, 4, 2, 7, 4, 2), intArrayOf(0, 2, 4, 6, 7, 6, 4, 2),
            intArrayOf(0, 4, 7, 9)).random()
        for (i in 0 until 32) {
            val chordDeg = prog[(i / 8) % prog.size]
            val d = chordDeg + shape[i % shape.size]
            set(tr, i, c.deg(d), if (i % 4 == 0) 112 else 88, D50)
        }
    }

    // ------------------------------------------------------------ TECHNO (basse roulante)
    private fun techno(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D16
        val rhythm = listOf(".xxx", "..x.", ".xx.", "..xx").random()
        for (i in 0 until 16) {
            if (!pattern(rhythm, i)) continue
            val n = when {
                i == 15 && Random.nextBoolean() -> c.deg(-2)
                Random.nextInt(8) == 0 -> c.deg(0, 1)
                else -> c.base
            }
            set(tr, i, n, if (i % 4 == 2) 115 else 92, listOf(D25, D25, D50).random())
        }
    }

    // ------------------------------------------------------------ BERLIN (ostinato école de Berlin)
    private fun berlin(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D16
        val motif = IntArray(4) { listOf(2, 3, 4, 5, 7, 9).random() }
        for (i in 0 until 16) {
            val d = if (i % 2 == 0) 0 else motif[(i / 2) % motif.size]
            val oct = if (i % 8 == 4) 1 else 0
            set(tr, i, c.deg(d, oct), if (i % 4 == 0) 115 else 90, D50)
        }
        if (Random.nextBoolean()) tr.length = 12                 // décalage polymétrique
    }

    // ------------------------------------------------------------ RIFF (power chords rock / métal)
    private fun riff(tr: SeqTrack, c: Ctx) {
        tr.length = 32; tr.div = Divs.D16
        val nat = intArrayOf(0, 2, 3, 5, 7, 8, 10)          // mineur naturel, en demi-tons
        fun pc(semi: Int) = c.base + semi
        val stabs = listOf(intArrayOf(3, 5, 7), intArrayOf(8, 7, 5), intArrayOf(10, 8, 7), intArrayOf(5, 3, 10)).random()
        val chug = listOf("x.x.xx.x", "xx.xx.x.", "x.xxx.xx", "x..xx.x.").random()
        for (bar in 0 until 2) {
            val o = bar * 16
            // 3 temps de « chugs » étouffés sur la fondamentale
            for (i in 0 until 12) if (pattern(chug, i)) set(tr, o + i, pc(0), if (i % 4 == 0) 118 else 92, D25, Chords.POWER)
            // 4e temps : accords plaqués qui montent / descendent
            val st = if (bar == 0) stabs else intArrayOf(stabs[2], stabs[1], 0)
            set(tr, o + 12, pc(nat.firstOrNull { it == st[0] } ?: st[0]), 124, D100, Chords.POWER)
            set(tr, o + 14, pc(st[1]), 120, D75, Chords.POWER)
            if (bar == 1) set(tr, o + 15, pc(st[2]), 126, D300, Chords.POWER)
        }
    }

    // ------------------------------------------------------------ DJENT (esprit bandes-son métal industriel)
    private fun djent(tr: SeqTrack, c: Ctx) {
        tr.div = Divs.D16
        val total = listOf(32, 30, 28, 32).random()        // longueurs impaires = décalage polymétrique
        tr.length = total
        var i = 0
        while (i < total) {
            val g = listOf(2, 3, 3, 4, 5).random()           // groupes 3+3+2… syncopés
            val accent = Random.nextInt(5) == 0
            when {
                accent -> {
                    // coup dissonant : seconde mineure ou triton
                    val semi = listOf(1, 6, 13, 0).random()
                    set(tr, i, c.base + semi, 127, D100, if (semi == 6) Chords.TRITONE else Chords.POWER)
                }
                Random.nextInt(4) == 0 ->
                    set(tr, i, c.base, 120, D25, Chords.FIFTH, ratch = listOf(2, 3).random())   // rafale
                else -> set(tr, i, c.base, 118, D25, Chords.FIFTH)
            }
            // remplissage du groupe : quelques chugs serrés
            for (k in 1 until g) if (Random.nextInt(3) == 0) set(tr, i + k, c.base, 96, D25, Chords.FIFTH)
            i += g
        }
        for (k in total until MAX_STEPS) tr.steps[k].reset()
    }

    // ------------------------------------------------------------ EBM (basse sequencée qui pousse)
    private fun ebm(tr: SeqTrack, c: Ctx) {
        tr.length = 16; tr.div = Divs.D16
        val shape = listOf("RRORRROR", "RORORORO", "RRRORRRO", "ROORROOR").random()
        val turn = listOf(-2, 3, 4, -3).random()            // degré du dernier temps
        for (i in 0 until 16) {
            val ch = shape[i % shape.length]
            val d = if (i >= 12) turn else 0
            val n = if (ch == 'O') c.deg(d, 1) else c.deg(d)
            set(tr, i, n, if (i % 4 == 0) 122 else 98, if (ch == 'O') D25 else D50)
        }
    }

    // ------------------------------------------------------------ SOLO (guitare / synthé lead)
    private fun solo(tr: SeqTrack, c: Ctx) {
        tr.length = 32; tr.div = Divs.D16
        val sc = if (c.minor) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)   // pentatonique
        fun note(d: Int) = c.base + 12 * Math.floorDiv(d, sc.size) + sc[Math.floorMod(d, sc.size)]
        var i = 0
        var d = Random.nextInt(3, 7)
        while (i < 30) {
            when (Random.nextInt(5)) {
                0 -> { // montée rapide
                    repeat(Random.nextInt(4, 8)) { if (i < 30) { set(tr, i, note(d), Random.nextInt(95, 120), D75); d++; i++ } }
                }
                1 -> { // descente
                    repeat(Random.nextInt(4, 8)) { if (i < 30) { set(tr, i, note(d), Random.nextInt(90, 118), D75); d--; i++ } }
                }
                2 -> { // trille rapide (ratchet)
                    repeat(Random.nextInt(2, 4)) { k ->
                        if (i < 30) { set(tr, i, note(d + (k % 2)), 110, D50, ratch = 2); i++ }
                    }
                }
                3 -> { // motif en groupes de 3
                    repeat(Random.nextInt(2, 4)) {
                        for (k in 0..2) if (i < 30) { set(tr, i, note(d + k), 104, D50); i++ }
                        d++
                    }
                }
                else -> { // note tenue « bend » + silence
                    set(tr, i, note(d), 122, D300)
                    i += 3
                }
            }
            d = d.coerceIn(-2, 12)
        }
        set(tr, 30, note(0) + 12, 124, D400)
    }
}
