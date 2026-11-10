package fr.rktrl.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Page 2 — séquenceur inspiré du Zaquencer (BCR32) :
 * 16 pistes x 1024 pas (pages A/B), réglages par piste, édition NOTE / VEL / DURÉE / ACCORD / COND / RATCH,
 * RANDOM, AUTO, EUCLIDE, COPIER/COLLER, KILL.
 */
class SequencerPage(private val act: Activity, private val seq: Sequencer) {

    var onGoController: (() -> Unit)? = null
    var onLogo: (() -> Unit)? = null
    var onLink: (() -> Unit)? = null

    private var current = 0
    private var follow = true
    lateinit var header: HeaderBar
        private set
    private lateinit var tabs: TrackTabsView
    private lateinit var grid: StepGridView
    private lateinit var pageLabel: TextView
    private lateinit var btnFollow: TdrButton
    private val paramBtns = ArrayList<TdrButton>()
    private lateinit var stCh: Stepper
    private lateinit var stLen: Stepper
    private lateinit var stDiv: Stepper
    private lateinit var stDir: Stepper
    private lateinit var stTr: Stepper
    private lateinit var stTempo: Stepper
    private lateinit var btnA: TdrButton
    private lateinit var btnB: TdrButton
    private lateinit var btnMute: TdrButton
    private var muteMode = false
    private lateinit var btnPlay: TdrButton
    private lateinit var btnKill: TdrButton
    private val ui = Handler(Looper.getMainLooper())
    private val disarm = Runnable { btnKill.active = false; btnKill.text = "KILL" }
    private val lastPos = IntArray(TRACKS) { -1 }
    private val flash = BooleanArray(TRACKS)
    private var loadingTrack = false

    // derniers réglages RANDOM
    private var rRoot = 0
    private var rOct = 3
    private var rScale = 1
    private var rRange = 1
    private var rDensity = 70
    private var rVel = true
    private var rDur = false

    // derniers réglages AUTO
    private var aType = 0
    private var aRoot = 0
    private var aOct = AutoGen.defaultOctave[0]
    private var aMinor = true

    // derniers réglages EUCLIDE
    private var eSteps = 16
    private var ePulses = 5
    private var eRot = 0
    private var ePulses2 = 0
    private var eRot2 = 0
    private var eMode = 0
    private var eNote = 36
    private var eKeep = true

    val view: View = build()

    private val tr: SeqTrack get() = seq.tracks[current]

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun build(): View {
        val c: Context = act
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Tdr.BG)
        root.isFocusableInTouchMode = true

        header = HeaderBar(c, "02", "SEQUENCER")
        header.onLogo = { onLogo?.invoke() }
        header.onLink = { onLink?.invoke() }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(34)))

        val mid = LinearLayout(c)
        mid.orientation = LinearLayout.HORIZONTAL
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- gauche : 16 pistes (2 x 8)
        tabs = TrackTabsView(c,
            // nom de la piste = nom du canal MIDI choisi (OPTIONS > NOMMER LES CANAUX MIDI)
            label = { Settings.channelNames[seq.tracks[it].channel - 1].ifBlank { "CH %02d".format(seq.tracks[it].channel) } },
            sub = { "%02d ${if (seq.tracks[it].page == 0) "A" else "B"}".format(it + 1) },
            isOn = { it == current },
            isDim = { seq.tracks[it].mute },
            hasContent = { seq.tracks[it].hasNotes() })
        tabs.flashing = { flash[it] }
        // mode MUTE : toucher une piste la coupe / la rallume ; sinon toucher = la sélectionner
        tabs.onTap = { if (muteMode) toggleMute(it) else selectTrack(it) }
        tabs.onLongPress = { toggleMute(it) }
        val tlp = LinearLayout.LayoutParams(c.dpi(150), ViewGroup.LayoutParams.MATCH_PARENT)
        tlp.leftMargin = c.dpi(6); tlp.topMargin = c.dpi(4); tlp.bottomMargin = c.dpi(2)
        mid.addView(tabs, tlp)

        // ---- centre : grille + navigation de pages
        val center = LinearLayout(c)
        center.orientation = LinearLayout.VERTICAL
        center.setPadding(c.dpi(4), c.dpi(4), c.dpi(4), c.dpi(2))
        mid.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        grid = StepGridView(c)
        grid.onEdited = { tabs.invalidate() }
        grid.onLongPress = { showStepExpression(it) }
        center.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(c)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(30))
        nlp.topMargin = c.dpi(3)
        center.addView(nav, nlp)
        val prev = TdrButton(c, "◀", Tdr.CYAN).onClick { follow = false; btnFollow.active = false; goPage(grid.viewPage - 1) }
        val next = TdrButton(c, "▶", Tdr.CYAN).onClick { follow = false; btnFollow.active = false; goPage(grid.viewPage + 1) }
        pageLabel = Tdr.label(c, "", 12f, Tdr.WHITE)
        pageLabel.gravity = Gravity.CENTER
        btnFollow = TdrButton(c, tr("SUIVRE", "FOLLOW"), Tdr.CYAN).onClick { follow = !follow; btnFollow.active = follow }
        btnFollow.active = follow
        nav.addView(prev, LinearLayout.LayoutParams(c.dpi(48), ViewGroup.LayoutParams.MATCH_PARENT))
        nav.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        nav.addView(next, LinearLayout.LayoutParams(c.dpi(48), ViewGroup.LayoutParams.MATCH_PARENT))
        val flp = LinearLayout.LayoutParams(c.dpi(84), ViewGroup.LayoutParams.MATCH_PARENT)
        flp.leftMargin = c.dpi(6)
        nav.addView(btnFollow, flp)

        // ---- droite : une seule colonne (PISTE puis GLOBAL)
        val scroll = ScrollView(c)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(c.dpi(4), c.dpi(2), c.dpi(10), c.dpi(4))
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mid.addView(scroll, LinearLayout.LayoutParams(c.dpi(196), ViewGroup.LayoutParams.MATCH_PARENT))

        fun section(t: String) {
            val tv = Tdr.label(c, "▌ $t", 10f, Tdr.YELLOW)
            tv.setPadding(0, c.dpi(5), 0, c.dpi(1))
            col.addView(tv)
        }
        fun add(v: View, h: Int) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(h))
            lp.topMargin = c.dpi(3)
            col.addView(v, lp)
        }
        fun pair(a: View, b: View, h: Int = 32) {
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            l1.rightMargin = c.dpi(5)
            row.addView(a, l1)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            add(row, h)
        }

        section(tr("PISTE", "TRACK"))

        stCh = Stepper(c, tr("CANAL MIDI", "MIDI CHANNEL"), 1, 16, tr.channel, fmt = { Settings.channelLabel(it) },
            onChange = { tr.channel = it; tr.releaseRequest = true; tabs.invalidate() }, compact = true)
        stLen = Stepper(c, tr("LONGUEUR (1–1024)", "LENGTH (1–1024)"), 1, MAX_STEPS, tr.length, editable = true,
            onChange = { tr.length = it; refreshPageNav(); grid.invalidate(); tabs.invalidate() }, compact = true)
        stDiv = Stepper(c, "DIVISION", 0, Divs.labels.size - 1, tr.div, fmt = { Divs.labels[it] },
            onChange = { tr.div = it }, compact = true)
        stDir = Stepper(c, tr("SENS DE LECTURE", "PLAY DIRECTION"), 0, 3, tr.dir, fmt = { Dirs.labels()[it] },
            onChange = { tr.dir = it }, compact = true, valueColor = Tdr.ACCENT)
        stTr = Stepper(c, tr("TRANSPO (±1024)", "TRANSPOSE (±1024)"), -1024, 1024, tr.transpose, editable = true,
            onChange = { tr.transpose = it; grid.invalidate() }, compact = true)
        add(stCh, 40); add(stLen, 40); add(stDiv, 40); add(stDir, 40); add(stTr, 40)

        btnA = TdrButton(c, "PAGE A", Tdr.CYAN).onClick { setAB(0) }
        btnB = TdrButton(c, "PAGE B", Tdr.CYAN).onClick { setAB(1) }
        pair(btnA, btnB)
        pair(
            TdrButton(c, tr("COPIER", "COPY"), Tdr.GREEN).onClick {
                seq.copy(tr)
                toast(tr("Piste ${current + 1} copiée", "Track ${current + 1} copied"))
            },
            TdrButton(c, tr("COLLER", "PASTE"), Tdr.GREEN).onClick {
                if (seq.paste(tr)) { refreshTrack(); toast(tr("Séquence collée", "Sequence pasted")) }
                else toast(tr("Rien à coller : copie d'abord une piste", "Nothing to paste: copy a track first"))
            })
        add(TdrButton(c, "AUTO", Tdr.ORANGE).onClick { showAuto() }, 32)
        add(TdrButton(c, tr("EUCLIDIEN", "EUCLIDEAN"), Tdr.VIOLET).onClick { showEuclid() }, 32)
        add(TdrButton(c, "RANDOM", Tdr.MAGENTA).onClick { showRandom() }, 32)

        section("GLOBAL")
        stTempo = Stepper(c, "TEMPO", 30, 300, seq.bpm, fmt = { "$it BPM" }, onChange = { seq.bpm = it; header.invalidate() },
            accent = Tdr.CYAN, compact = true)
        add(stTempo, 40)
        add(Stepper(c, "SWING", 50, 75, seq.swing, fmt = { "$it%" }, onChange = { seq.swing = it },
            accent = Tdr.CYAN, compact = true), 40)
        btnKill = TdrButton(c, "KILL", Tdr.YELLOW)
        btnKill.hazard = true
        btnKill.onClick { onKill() }
        add(btnKill, 42)

        // ---- barre du bas
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(c.dpi(6), c.dpi(4), c.dpi(8), c.dpi(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(56)))

        val modes = listOf(
            "NOTE" to StepParam.NOTE, "VEL" to StepParam.VEL,
            tr("DURÉE", "LENGTH") to StepParam.DUR, tr("ACCORD", "CHORD") to StepParam.CHORD,
            "COND" to StepParam.COND, "RATCH" to StepParam.RATCH
        )
        modes.forEachIndexed { idx, (name, prm) ->
            val b = TdrButton(c, name, Tdr.WHITE, "M-0${idx + 1}").onClick {
                grid.param = prm
                paramBtns.forEachIndexed { j, pb -> pb.active = j == idx }
            }
            b.active = idx == 0
            paramBtns.add(b)
        }
        btnPlay = TdrButton(c, "▶ PLAY", Tdr.GREEN, "T-01").onClick { seq.toggle(); refreshTransport() }
        btnMute = TdrButton(c, "MUTE", Tdr.MAGENTA, "M-ON").onClick {
            muteMode = !muteMode
            btnMute.active = muteMode
            btnMute.text = if (muteMode) tr("MUTE ▸ PISTES", "MUTE ▸ TRACKS") else "MUTE"
        }
        val btnCtrl = TdrButton(c, if (Tdr.field) "← CONTROLLER" else "◀◀ CONTROLLER", if (Tdr.field) Tdr.ACCENT else Tdr.YELLOW,
            if (Tdr.field) "" else "P-01").onClick { onGoController?.invoke() }
        btnCtrl.active = Tdr.field

        val all = paramBtns + listOf(btnPlay, btnMute, btnCtrl)
        all.forEachIndexed { idx, b ->
            val w = when (idx) { 6 -> 1.2f; 8 -> 1.7f; else -> 1f }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
            lp.rightMargin = c.dpi(if (idx == 5) 14 else 5)
            bar.addView(b, lp)
        }

        selectTrack(0)
        refreshTransport()
        return root
    }

    private fun hideKeyboard(v: View) {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Tempo changé ailleurs (TAP BPM du Destroy Mode). */
    fun refreshTempo() = stTempo.update(seq.bpm, false)

    private fun toggleMute(i: Int) {
        val t = seq.tracks[i]
        t.mute = !t.mute
        tabs.invalidate()
    }

    private fun pages(): Int = (tr.length + PAGE_STEPS - 1) / PAGE_STEPS

    private fun goPage(p: Int) {
        grid.viewPage = p.coerceIn(0, pages() - 1)
        refreshPageNav()
    }

    private fun refreshPageNav() {
        if (grid.viewPage > pages() - 1) grid.viewPage = pages() - 1
        val first = grid.viewPage * PAGE_STEPS + 1
        val last = minOf((grid.viewPage + 1) * PAGE_STEPS, tr.length)
        pageLabel.text = tr("PAGE ${grid.viewPage + 1}/${pages()} · PAS $first–$last",
            "PAGE ${grid.viewPage + 1}/${pages()} · STEPS $first–$last")
    }

    private fun setAB(p: Int) {
        if (tr.page != p) { tr.page = p; tr.releaseRequest = true }
        btnA.active = tr.page == 0
        btnB.active = tr.page == 1
        grid.track = tr
        refreshPageNav()
        tabs.invalidate()
    }

    private fun selectTrack(t: Int) {
        current = t
        grid.track = tr
        grid.accent = Tdr.trackColor(t)
        grid.viewPage = 0
        stCh.update(tr.channel, false)
        stLen.update(tr.length, false)
        stDiv.update(tr.div, false)
        stDir.update(tr.dir, false)
        stTr.update(tr.transpose, false)
        btnA.active = tr.page == 0
        btnB.active = tr.page == 1
        ui.removeCallbacks(disarm); disarm.run()
        refreshPageNav()
        tabs.invalidate()
    }

    private fun refreshTrack() {
        val keep = grid.viewPage
        selectTrack(current)
        goPage(keep)
        grid.invalidate()
    }

    private fun refreshTransport() {
        if (seq.running) {
            btnPlay.text = "■ STOP"
            btnPlay.accent = Tdr.RED
            btnPlay.active = !Tdr.field
        } else {
            btnPlay.text = "▶ PLAY"
            btnPlay.accent = Tdr.GREEN
            btnPlay.active = false
        }
        header.running = seq.running
    }

    /** Appelé à chaque pas joué (thread principal). */
    fun onStep() {
        for (i in 0 until TRACKS) {
            val t = seq.tracks[i]
            val pos = t.pos
            flash[i] = pos >= 0 && pos != lastPos[i] && pos < MAX_STEPS && t.steps[pos].on && !t.mute
            lastPos[i] = pos
        }
        if (follow && seq.running && tr.pos >= 0) {
            val p = tr.pos / PAGE_STEPS
            if (p != grid.viewPage) goPage(p)
        }
        grid.invalidate()
        tabs.invalidate()
        refreshTransport()
    }

    // ------------------------------------------------------------ KILL (deux appuis)
    private fun onKill() {
        if (!btnKill.active) {
            btnKill.active = true
            btnKill.text = tr("CONFIRMER ?", "CONFIRM ?")
            ui.removeCallbacks(disarm)
            ui.postDelayed(disarm, 2500)
        } else {
            seq.kill(tr)
            ui.removeCallbacks(disarm); disarm.run()
            refreshTrack()
        }
    }

    // ------------------------------------------------------------ MODE AVANCÉ D'UN PAS (appui long)
    private fun showStepExpression(i: Int) {
        val c: Context = act
        val st = tr.steps[i]
        val d = TdrDialog(c, tr("PAS ${i + 1} · EXPRESSION", "STEP ${i + 1} · EXPRESSION"))
        d.note(tr("Envoyé juste avant la note de ce pas. Un réglage éteint n'envoie rien.",
            "Sent right before this step's note. A switched-off setting sends nothing."))

        // chaque réglage : un interrupteur + une valeur
        fun line(label: String, on: Boolean, lo: Int, hi: Int, v: Int, fmt: (Int) -> String): Pair<TdrButton, Stepper> {
            d.section(label)
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            val b = TdrButton(c, if (on) "ON" else "OFF", Tdr.YELLOW)
            b.active = on
            val stp = Stepper(c, "", lo, hi, v, fmt = fmt)
            b.onClick { b.active = !b.active; b.text = if (b.active) "ON" else "OFF" }
            row.addView(b, LinearLayout.LayoutParams(c.dpi(64), ViewGroup.LayoutParams.MATCH_PARENT))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.leftMargin = c.dpi(8)
            row.addView(stp, lp)
            d.row(row, 40)
            return b to stp
        }
        val pan = line("PANORAMIQUE (CC10)", st.pan >= 0, 0, 127, if (st.pan >= 0) st.pan else 64) {
            when { it < 64 -> "L ${64 - it}"; it > 64 -> "R ${it - 64}"; else -> "C" }
        }
        val mod = line("MODULATION (CC1)", st.mod >= 0, 0, 127, if (st.mod >= 0) st.mod else 0) { "$it" }
        val bendInit = if (st.bend >= 0) ((st.bend - 8192) * 100.0 / 8192).toInt() else 0
        val bend = line("PITCH BEND", st.bend >= 0, -100, 100, bendInit) { if (it > 0) "+$it%" else "$it%" }

        d.section(tr("ARTICULATION", "ARTICULATION"))
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        val bAcc = TdrButton(c, "ACCENT", Tdr.YELLOW)
        bAcc.active = st.accent
        bAcc.onClick { bAcc.active = !bAcc.active }
        val bSl = TdrButton(c, "SLIDE", Tdr.YELLOW)
        bSl.active = st.slide
        bSl.onClick { bSl.active = !bSl.active }
        val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); l1.rightMargin = c.dpi(6)
        row.addView(bAcc, l1)
        row.addView(bSl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        d.row(row, 40)
        d.note(tr("ACCENT : vélocité au maximum. SLIDE : la note se lie à la suivante (legato + portamento CC65) — idéal pour l'acid.",
            "ACCENT: full velocity. SLIDE: the note ties into the next one (legato + portamento CC65) — great for acid."))

        d.action(tr("EFFACER", "CLEAR"), Tdr.DIM) {
            st.pan = -1; st.mod = -1; st.bend = -1; st.accent = false; st.slide = false
            grid.invalidate(); d.dismiss()
        }
        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("VALIDER", "OK"), primary = true) {
            st.pan = if (pan.first.active) pan.second.value else -1
            st.mod = if (mod.first.active) mod.second.value else -1
            st.bend = if (bend.first.active) (8192 + bend.second.value * 8191 / 100).coerceIn(0, 16383) else -1
            st.accent = bAcc.active
            st.slide = bSl.active
            st.on = true
            grid.invalidate(); tabs.invalidate()
            d.dismiss()
        }
        d.show()
    }

    // ------------------------------------------------------------ EUCLIDIEN
    private fun showEuclid() {
        val c: Context = act
        val d = TdrDialog(c, tr("EUCLIDIEN // PISTE ${current + 1}", "EUCLIDEAN // TRACK ${current + 1}"), Tdr.VIOLET)
        eSteps = minOf(tr.length, 64)
        val preview = Tdr.label(c, "", 13f, Tdr.VIOLET, Tdr.mono)
        fun update() {
            val n = eSteps
            fun layer(k: Int, r: Int) = BooleanArray(n) { i ->
                val kk = k.coerceAtMost(n); kk > 0 && (Math.floorMod(i - r, n) * kk) % n < kk
            }
            val a = layer(ePulses, eRot); val b = layer(ePulses2, eRot2)
            preview.text = buildString {
                for (i in 0 until n) {
                    val hit = when (eMode) { 1 -> a[i] || b[i]; 2 -> a[i] xor b[i]; 3 -> a[i] && b[i]; 4 -> a[i] && !b[i]; else -> a[i] }
                    append(if (hit) "●" else "·")
                    if (i % 16 == 15) append("\n") else if (i % 4 == 3) append(" ")
                }
            }
        }
        d.note(tr("Répartit au mieux un nombre de notes sur un nombre de pas, comme le mode euclidien des Elektron.",
            "Spreads a number of hits as evenly as possible over a number of steps, like Elektron's euclidean mode."))
        d.section(tr("COUCHE 1", "LAYER 1"))
        d.row(Stepper(c, tr("PAS", "STEPS"), 1, 64, eSteps, onChange = { eSteps = it; update() }, accent = Tdr.VIOLET))
        d.row(Stepper(c, tr("NOTES (PULSES)", "PULSES"), 0, 64, ePulses, onChange = { ePulses = it; update() }, accent = Tdr.VIOLET))
        d.row(Stepper(c, tr("ROTATION", "ROTATION"), 0, 63, eRot, onChange = { eRot = it; update() }, accent = Tdr.VIOLET))
        d.section(tr("COUCHE 2 (OPTIONNELLE)", "LAYER 2 (OPTIONAL)"))
        d.row(Stepper(c, tr("NOTES (PULSES)", "PULSES"), 0, 64, ePulses2, onChange = { ePulses2 = it; update() }, accent = Tdr.VIOLET))
        d.row(Stepper(c, "ROTATION", 0, 63, eRot2, onChange = { eRot2 = it; update() }, accent = Tdr.VIOLET))
        d.body.addView(choiceGrid(c, arrayOf("—", tr("OU", "OR"), "XOR", tr("ET", "AND"), tr("MOINS", "MINUS")), 5, eMode, Tdr.VIOLET) {
            eMode = it; update()
        })
        d.section("NOTE")
        d.row(Stepper(c, tr("NOTE DES NOUVEAUX PAS", "NOTE FOR NEW STEPS"), 0, 127, eNote, fmt = { Tdr.noteName(it) },
            onChange = { eNote = it }, accent = Tdr.VIOLET))
        val bKeep = TdrButton(c, tr("GARDER LES NOTES EXISTANTES", "KEEP EXISTING NOTES"), Tdr.VIOLET)
        bKeep.active = eKeep
        bKeep.onClick { eKeep = !eKeep; bKeep.active = eKeep }
        d.row(bKeep)
        d.section(tr("APERÇU", "PREVIEW"))
        d.body.addView(preview)
        update()
        d.action(tr("FERMER", "CLOSE"), Tdr.DIM) { d.dismiss() }
        d.action(tr("GÉNÉRER", "GENERATE"), primary = true) {
            seq.euclid(tr, eSteps, ePulses, eRot, ePulses2, eRot2, eMode, eNote, eKeep)
            refreshTrack()
            d.dismiss()
        }
        d.show()
    }

    // ------------------------------------------------------------ RANDOM
    private fun showRandom() {
        val c: Context = act
        val d = TdrDialog(c, "RANDOM // TRACK ${current + 1}", Tdr.MAGENTA)

        d.section(tr("1 · NOTE FONDAMENTALE", "1 · ROOT NOTE"))
        d.body.addView(choiceGrid(c, Tdr.ROOTS, 6, rRoot, Tdr.MAGENTA) { rRoot = it })
        d.row(Stepper(c, "OCTAVE", 0, 7, rOct, onChange = { rOct = it }, accent = Tdr.MAGENTA))

        d.section(tr("2 · GAMME", "2 · SCALE"))
        d.body.addView(choiceGrid(c, Array(Scales.names.size) { Scales.name(it) }, 4, rScale, Tdr.MAGENTA) { rScale = it })

        d.section(tr("3 · RÉGLAGES", "3 · SETTINGS"))
        d.row(Stepper(c, tr("ÉTENDUE (OCTAVES)", "RANGE (OCTAVES)"), 1, 3, rRange, onChange = { rRange = it }, accent = Tdr.MAGENTA))
        d.row(Stepper(c, tr("DENSITÉ DES PAS", "STEP DENSITY"), 10, 100, rDensity, step = 5, fmt = { "$it%" },
            onChange = { rDensity = it }, accent = Tdr.MAGENTA))
        val bVel = TdrButton(c, tr("VÉLOCITÉ ALÉATOIRE", "RANDOM VELOCITY"), Tdr.MAGENTA)
        bVel.active = rVel
        bVel.onClick { rVel = !rVel; bVel.active = rVel }
        d.row(bVel)
        val bDur = TdrButton(c, tr("DURÉE ALÉATOIRE", "RANDOM LENGTH"), Tdr.MAGENTA)
        bDur.active = rDur
        bDur.onClick { rDur = !rDur; bDur.active = rDur }
        d.row(bDur)
        d.note(tr("Remplit toute la longueur de la piste. Rouvre RANDOM pour un nouveau tirage, même en lecture.",
            "Fills the whole track length. Open RANDOM again for a new roll, even while playing."))

        d.action(tr("FERMER", "CLOSE"), Tdr.DIM) { d.dismiss() }
        d.action(tr("GÉNÉRER", "GENERATE"), primary = true) {
            seq.randomize(tr, rRoot, rOct, rScale, rRange, rDensity, rVel, rDur)
            grid.invalidate(); tabs.invalidate()
            d.dismiss()
        }
        d.show()
    }

    // ------------------------------------------------------------ AUTO
    private fun showAuto() {
        val c: Context = act
        val d = TdrDialog(c, "AUTO // TRACK ${current + 1}", Tdr.ORANGE)

        lateinit var octStep: Stepper
        lateinit var desc: TextView
        d.section(tr("1 · TYPE DE SÉQUENCE", "1 · SEQUENCE TYPE"))
        d.body.addView(choiceGrid(c, AutoGen.types, 4, aType, Tdr.ORANGE) {
            aType = it
            octStep.update(AutoGen.defaultOctave[it])
            desc.text = describe(it)
        })
        desc = d.note(describe(aType))

        d.section(tr("2 · FONDAMENTALE", "2 · ROOT NOTE"))
        d.body.addView(choiceGrid(c, Tdr.ROOTS, 6, aRoot, Tdr.ORANGE) { aRoot = it })
        octStep = Stepper(c, "OCTAVE", 0, 7, aOct, onChange = { aOct = it }, accent = Tdr.ORANGE)
        d.row(octStep)

        d.section(tr("3 · COULEUR", "3 · MOOD"))
        d.body.addView(choiceGrid(c, arrayOf(tr("MINEUR", "MINOR"), tr("MAJEUR", "MAJOR")), 2,
            if (aMinor) 0 else 1, Tdr.ORANGE) { aMinor = it == 0 })
        d.note(tr("AUTO garde tes derniers choix : rouvre-le et GÉNÉRER pour une nouvelle variante. Il règle aussi la longueur et la division de la piste.",
            "AUTO remembers your last choices: open it again and GENERATE for a new variation. It also sets the track length and division."))

        d.action(tr("FERMER", "CLOSE"), Tdr.DIM) { d.dismiss() }
        d.action(tr("GÉNÉRER", "GENERATE"), primary = true) {
            AutoGen.generate(tr, aType, aRoot, aOct, aMinor)
            refreshTrack()
            d.dismiss()
        }
        d.show()
    }

    private fun describe(t: Int): String = when (t) {
        0 -> tr("Basse house / disco / funk sur 2 mesures.", "House / disco / funk bass over 2 bars.")
        1 -> tr("Mélodie en question / réponse, notes liées.", "Call-and-response melody with tied notes.")
        2 -> tr("Ligne acid façon 303 : accents et slides (activer le portamento du synthé).",
            "303-style acid line: accents and slides (turn on the synth's portamento).")
        3 -> tr("Accords longs et mouvants sur 4 mesures (synthé polyphonique).",
            "Long, moving chords over 4 bars (polyphonic synth).")
        4 -> tr("Une note tenue en continu (∞).", "One continuously held note (∞).")
        5 -> tr("Arpège qui suit une grille d'accords.", "Arpeggio following a chord progression.")
        6 -> tr("Basse techno roulante en contretemps.", "Rolling off-beat techno bass.")
        7 -> tr("Ostinato hypnotique façon école de Berlin.", "Hypnotic Berlin-school ostinato.")
        8 -> tr("Riff rock / métal en power chords : chugs étouffés + accords plaqués (synthé polyphonique).",
            "Rock / metal power-chord riff: palm-muted chugs + chord stabs (polyphonic synth).")
        9 -> tr("Chugs graves syncopés, rafales et dissonances, esprit bandes-son métal industriel. Longueur parfois impaire.",
            "Low syncopated chugs, bursts and dissonances, industrial-metal soundtrack style. Sometimes odd length.")
        10 -> tr("Basse EBM en doubles croches, octaves qui claquent.", "Driving EBM 16th-note bass with snapping octaves.")
        else -> tr("Solo de guitare / synthé : montées, descentes, trilles et notes tenues (pentatonique).",
            "Guitar / synth solo: runs, trills and held notes (pentatonic).")
    }
}
