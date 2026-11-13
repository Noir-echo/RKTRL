package fr.rktrl.app

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Page 1 — contrôleur : 128 boutons ronds sur 4 pages de 32, fader avec accroche (soft takeover)
 * et LATCH (retour glissé au centre), EDIT / GROUP / UNDO / PLAY / DESTROY / SEQUENCER.
 */
class ControllerPage(private val act: Activity, private val midi: MidiHub, private val seq: Sequencer) {

    val knobs: MutableList<KnobConfig> = Store.loadKnobs(act)

    private val knobViews = ArrayList<KnobView>()
    private val labelViews = ArrayList<EditText>()
    private val selected = LinkedHashSet<Int>()
    private val caught = HashSet<Int>()
    private val undoStack = ArrayDeque<List<Pair<Int, Int>>>()
    private var snapshot: Map<Int, Int>? = null
    private var editMode = false
    private var groupMode = false
    private var page = 0
    private var binding = false
    private val pageBtns = ArrayList<TdrButton>()
    private lateinit var btnLatch: TdrButton
    private var latchOn = true
    /** FORCE : pas d'accroche, le fader impose directement sa valeur (sauts possibles). */
    private var force = false

    lateinit var header: HeaderBar
        private set
    private lateinit var fader: FaderView
    private lateinit var info: TextView
    private lateinit var btnEdit: TdrButton
    private lateinit var btnGroup: TdrButton
    private lateinit var btnUndo: TdrButton
    private lateinit var btnPlay: TdrButton

    var onGoSequencer: (() -> Unit)? = null
    var onGoDestroy: (() -> Unit)? = null
    var onLogo: (() -> Unit)? = null
    var onLink: (() -> Unit)? = null

    val view: View = build()

    // ------------------------------------------------------------------ construction
    private fun build(): View {
        val c: Context = act
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Tdr.BG)
        root.isFocusableInTouchMode = true      // évite que le clavier s'ouvre au démarrage

        header = HeaderBar(c, "01", "CONTROLLER")
        header.onLogo = { onLogo?.invoke() }
        header.onLink = { onLink?.invoke() }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(34)))

        val mid = LinearLayout(c)
        mid.orientation = LinearLayout.HORIZONTAL
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- grille 4 x 8 (deux tiers de l'écran)
        val grid = LinearLayout(c)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(c.dpi(6), c.dpi(4), c.dpi(4), c.dpi(2))
        mid.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

        for (row in 0 until 4) {
            val rowL = LinearLayout(c)
            rowL.orientation = LinearLayout.HORIZONTAL
            grid.addView(rowL, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (col in 0 until 8) {
                val i = row * 8 + col
                val cell = LinearLayout(c)
                cell.orientation = LinearLayout.VERTICAL
                cell.setPadding(c.dpi(2), c.dpi(1), c.dpi(2), c.dpi(2))

                val kv = KnobView(c, i)
                kv.cfg = knobs[i]
                kv.onTap = { onKnobTap(it) }      // `it` = numéro global du bouton (0–127)
                knobViews.add(kv)
                cell.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

                val et = tdrEdit(c, knobs[i].label, 10f, 14)
                et.hint = "· · ·"
                et.gravity = Gravity.CENTER
                et.setPadding(c.dpi(2), c.dpi(1), c.dpi(2), c.dpi(1))
                et.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (binding) return
                        knobs[page * 32 + i].label = s?.toString() ?: ""
                        refreshInfo()
                    }
                })
                et.setOnEditorActionListener { v, _, _ ->
                    v.clearFocus()
                    hideKeyboard(v)
                    true
                }
                labelViews.add(et)
                cell.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                rowL.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }

        // --- partie droite : fader + écran d'infos
        val right = LinearLayout(c)
        right.orientation = LinearLayout.HORIZONTAL
        right.setPadding(c.dpi(6), c.dpi(6), c.dpi(8), c.dpi(4))
        mid.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val infoCol = LinearLayout(c)
        infoCol.orientation = LinearLayout.VERTICAL
        right.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // pages de boutons : 4 x 32 = 128
        val pages = LinearLayout(c)
        pages.orientation = LinearLayout.HORIZONTAL
        for (p in 0 until 4) {
            val b = TdrButton(c, "ABCD"[p].toString(), Tdr.trackColor(p)).onClick { showKnobPage(p) }
            pageBtns.add(b)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (p < 3) lp.rightMargin = c.dpi(4)
            pages.addView(b, lp)
        }
        infoCol.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(32)))

        info = Tdr.label(c, "", 11f, Tdr.WHITE, Tdr.mono)
        info.setLineSpacing(0f, 1.15f)
        info.background = PanelDrawable(c, if (Tdr.field) Tdr.WHITE else Tdr.GRID, Tdr.PANEL, 10f)
        info.setPadding(c.dpi(10), c.dpi(10), c.dpi(8), c.dpi(8))
        val ilp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        ilp.topMargin = c.dpi(5); ilp.bottomMargin = c.dpi(5)
        infoCol.addView(info, ilp)

        // LATCH du fader : allumé = il reste où on le lâche ; éteint = il revient au centre en glissant
        // LATCH : appui court = allumé / éteint ; appui long = mode FORCE (accent)
        btnLatch = TdrButton(c, "LATCH", if (Tdr.field) Tdr.WHITE else Tdr.RED)
        btnLatch.emergency = true
        latchOn = Store.loadFaderLatch(c)
        force = Store.loadFaderForce(c)
        btnLatch.onClick {
            if (force) force = false else latchOn = !latchOn
            applyLatch()
        }
        btnLatch.setOnLongClickListener {
            force = !force
            btnLatch.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            applyLatch()
            true
        }
        infoCol.addView(btnLatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(44)))

        fader = FaderView(c)
        fader.pos = Store.loadFader(c)
        fader.onStart = { onFaderStart() }
        fader.onMove = { a, b -> onFaderMove(a, b) }
        fader.onEnd = { onFaderEnd() }
        applyLatch()
        val flp = LinearLayout.LayoutParams(c.dpi(92), ViewGroup.LayoutParams.MATCH_PARENT)
        flp.leftMargin = c.dpi(8)
        right.addView(fader, flp)

        // --- barre du bas
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(c.dpi(6), c.dpi(4), c.dpi(8), c.dpi(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(56)))

        btnEdit = TdrButton(c, "EDIT", Tdr.YELLOW, "E-01").onClick { toggleEdit() }
        btnGroup = TdrButton(c, "GROUP", Tdr.CYAN, "G-02").onClick { toggleGroup() }
        // appui long : choisir un ou plusieurs canaux MIDI → tous leurs boutons sont sélectionnés
        btnGroup.setOnLongClickListener {
            btnGroup.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showChannelGroup()
            true
        }
        btnUndo = TdrButton(c, "UNDO", Tdr.ORANGE, "U-03").onClick { undo() }
        btnPlay = TdrButton(c, "▶ PLAY", Tdr.GREEN, "T-01").onClick { seq.toggle(); refreshTransport() }
        val btnDestroy = TdrButton(c, "DESTROY MODE", Tdr.YELLOW)
        btnDestroy.hazard = true
        btnDestroy.onClick { onGoDestroy?.invoke() }
        val btnSeq = TdrButton(c, if (Tdr.field) "SEQUENCER →" else "SEQUENCER  ▶▶", if (Tdr.field) Tdr.ACCENT else Tdr.MAGENTA,
            if (Tdr.field) "" else "P-02").onClick { onGoSequencer?.invoke() }
        btnSeq.active = Tdr.field
        listOf(btnEdit, btnGroup, btnUndo, btnPlay, btnDestroy, btnSeq).forEachIndexed { idx, b ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (idx >= 4) 1.5f else 1f)
            lp.rightMargin = c.dpi(6)
            bar.addView(b, lp)
        }

        showKnobPage(0)
        refreshTransport()
        return root
    }

    /** Affiche une des 4 pages de 32 boutons (la sélection est conservée d'une page à l'autre). */
    private fun showKnobPage(p: Int) {
        act.currentFocus?.let { it.clearFocus(); hideKeyboard(it) }
        page = p
        binding = true
        knobViews.forEachIndexed { slot, kv ->
            val g = p * 32 + slot
            kv.index = g
            kv.cfg = knobs[g]
            labelViews[slot].setText(knobs[g].label)
        }
        binding = false
        pageBtns.forEachIndexed { i, b -> b.active = i == p }
        refreshAll()
    }

    /** Met à jour le bouton PLAY/STOP (appelé aussi à chaque pas du séquenceur). */
    fun refreshTransport() {
        btnPlay.text = if (seq.running) "■ STOP" else "▶ PLAY"
        btnPlay.accent = if (seq.running) Tdr.RED else Tdr.GREEN
        btnPlay.active = seq.running && !Tdr.field     // FIELD : seul le ■ passe en accent
        header.running = seq.running
    }

    private fun hideKeyboard(v: View) {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ------------------------------------------------------------------ logique
    /** Valeur (flottante) que le fader représente pour ce bouton, selon sa plage. */
    private fun mapped(k: KnobConfig, pos: Float): Float = k.min + pos * (k.max - k.min)

    private fun onKnobTap(i: Int) {
        act.currentFocus?.let { it.clearFocus(); hideKeyboard(it) }
        when {
            editMode -> {
                toggleEdit()
                showEditDialog(i)
            }
            groupMode -> {
                if (!selected.add(i)) selected.remove(i)
                selectionChanged()
            }
            else -> {
                selected.clear()
                selected.add(i)
                selectionChanged()
            }
        }
    }

    private fun selectionChanged() {
        caught.retainAll(selected)
        recheckCaught()
        refreshAll()
    }

    /** Un bouton dont la valeur coïncide déjà avec la position du fader est accroché d'office. */
    private fun recheckCaught() {
        if (force) { caught.addAll(selected); return }
        for (i in selected) {
            val k = knobs[i]
            val inSync = abs(mapped(k, fader.pos) - k.value) < 0.5f
            if (inSync) caught.add(i)
        }
    }

    // ---- envoi des CC : limité en débit (le Bluetooth MIDI sature si on envoie trop vite)
    private val ccHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingCC = LinkedHashMap<Int, Int>()          // clé (canal << 7 | cc) → valeur
    private val sentAt = HashMap<Int, Long>()                  // dernier envoi, pour ignorer l'écho de l'instrument
    private var flushPosted = false
    private var faderTouching = false
    private val flush = Runnable {
        flushPosted = false
        val now = android.os.SystemClock.uptimeMillis()
        for ((key, v) in pendingCC) {
            midi.sendCC(key shr 7, key and 0x7F, v)
            sentAt[key] = now
        }
        pendingCC.clear()
    }

    private fun queueCC(ch: Int, cc: Int, v: Int) {
        val key = (ch shl 7) or cc
        pendingCC[key] = v
        sentAt[key] = android.os.SystemClock.uptimeMillis()
        if (!flushPosted) { flushPosted = true; ccHandler.postDelayed(flush, 6) }
    }

    /** Valeur connue d'un CC (utilisée par le Destroy Mode pour restaurer le filtre). */
    fun knownValue(ch: Int, cc: Int): Int? = knobs.firstOrNull { it.channel == ch && it.cc == cc }?.value

    private fun onFaderStart() {
        faderTouching = true
        snapshot = selected.associateWith { knobs[it].value }
    }

    private fun applyLatch() {
        if (force) {
            btnLatch.text = "FORCE"
            btnLatch.emergency = false
            btnLatch.accent = Tdr.ACCENT
            btnLatch.active = true
        } else {
            btnLatch.text = "LATCH"
            btnLatch.emergency = true
            btnLatch.accent = if (Tdr.field) Tdr.WHITE else Tdr.RED
            btnLatch.active = latchOn
        }
        fader.springBack = !latchOn && !force
        Store.saveFaderLatch(act, latchOn)
        Store.saveFaderForce(act, force)
        if (force) caught.addAll(selected) else { caught.clear(); recheckCaught() }
        if (::btnUndo.isInitialized) refreshAll()      // pendant la construction, la barre du bas n'existe pas encore
    }

    private fun onFaderMove(a: Float, b: Float) {
        for (i in selected) {
            val k = knobs[i]
            if (force) caught.add(i)
            if (i !in caught) {
                // accroche : la valeur réelle doit se trouver entre l'ancienne et la nouvelle position
                val va = mapped(k, a)
                val vb = mapped(k, b)
                val lo = min(k.min, k.max).toFloat()
                val hi = max(k.min, k.max).toFloat()
                val target = k.value.toFloat().coerceIn(lo, hi)
                if (target >= min(va, vb) - 0.5f && target <= max(va, vb) + 0.5f) caught.add(i) else continue
            }
            val nv = mapped(k, b).roundToInt().coerceIn(0, 127)
            if (nv != k.value) {
                queueCC(k.channel, k.cc, nv)
                setKnobValue(k.channel, k.cc, nv, i)
            }
        }
        refreshAll()
    }

    private fun onFaderEnd() {
        faderTouching = false
        val snap = snapshot ?: return
        snapshot = null
        val changes = snap.filter { (i, v) -> knobs[i].value != v }.map { it.key to it.value }
        if (changes.isNotEmpty()) {
            undoStack.addLast(changes)
            while (undoStack.size > 64) undoStack.removeFirst()
        }
        Store.saveFader(act, fader.pos)
        refreshAll()
    }

    /** Met à jour tous les boutons partageant ce canal/CC (et revérifie leur accroche). */
    private fun setKnobValue(ch: Int, cc: Int, v: Int, source: Int) {
        knobs.forEachIndexed { j, k ->
            if (k.channel == ch && k.cc == cc) {
                k.value = v
                if (j != source && j in caught && abs(mapped(k, fader.pos) - v) >= 0.5f) caught.remove(j)
            }
        }
    }

    /** CC reçu de l'instrument : on connaît maintenant sa valeur réelle. */
    fun onExternalCC(ch: Int, cc: Int, v: Int) {
        // Beaucoup d'instruments renvoient (écho) les CC reçus avec un léger retard. Sans ce filtre,
        // l'écho d'une ancienne valeur « décrochait » le fader quand on le bougeait vite.
        val key = (ch shl 7) or cc
        val last = sentAt[key]
        if (last != null && android.os.SystemClock.uptimeMillis() - last < 600) return
        if (faderTouching && selected.any { knobs[it].channel == ch && knobs[it].cc == cc }) return
        var hit = false
        knobs.forEachIndexed { j, k ->
            if (k.channel == ch && k.cc == cc && k.value != v) {
                k.value = v
                hit = true
                if (j in caught && abs(mapped(k, fader.pos) - v) >= 0.5f) caught.remove(j)
            }
        }
        if (hit) refreshAll()
    }

    private fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        for ((i, v) in last) {
            val k = knobs[i]
            queueCC(k.channel, k.cc, v)
            setKnobValue(k.channel, k.cc, v, -1)
            if (abs(mapped(k, fader.pos) - v) >= 0.5f) caught.remove(i)
        }
        refreshAll()
    }

    private fun toggleEdit() {
        editMode = !editMode
        if (editMode && groupMode) groupMode = false
        refreshAll()
    }

    /** GROUP : 1er appui = mode sélection multiple ; 2e appui = on sort et tout est désélectionné. */
    /** GROUP par canal : sélectionne les boutons (des 4 banques) assignés aux canaux choisis. */
    private fun showChannelGroup() {
        val c: Context = act
        val d = TdrDialog(c, tr("GROUP // PAR CANAL MIDI", "GROUP // BY MIDI CHANNEL"))
        d.note(tr("Tous les boutons des 4 banques réglés sur ces canaux seront sélectionnés. Appuie de nouveau sur GROUP pour tout désélectionner.",
            "Every knob in the 4 banks set to these channels will be selected. Press GROUP again to deselect everything."))
        val chosen = BooleanArray(16)
        for (r in 0 until 2) {
            val row = LinearLayout(c)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until 8) {
                val ch = r * 8 + k + 1
                val n = knobs.count { it.channel == ch }
                val name = Settings.channelNames[ch - 1].ifBlank { "CH %02d".format(ch) }
                val b = TdrButton(c, name, Tdr.YELLOW, "%02d · $n".format(ch))
                b.isEnabled = n > 0
                b.onClick { chosen[ch - 1] = !chosen[ch - 1]; b.active = chosen[ch - 1] }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.rightMargin = c.dpi(4)
                row.addView(b, lp)
            }
            d.row(row, 48)
        }
        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("SÉLECTIONNER", "SELECT"), primary = true) {
            selected.clear()
            knobs.forEachIndexed { i, k -> if (chosen[k.channel - 1]) selected.add(i) }
            if (selected.isNotEmpty()) {
                groupMode = true
                editMode = false
            }
            selectionChanged()
            d.dismiss()
        }
        d.show()
    }

    private fun toggleGroup() {
        groupMode = !groupMode
        if (groupMode) {
            editMode = false
        } else {
            selected.clear()
            caught.clear()
        }
        refreshAll()
    }

    // ------------------------------------------------------------------ EDIT
    private fun showEditDialog(i: Int) {
        val c: Context = act
        val k = knobs[i]
        val d = TdrDialog(c, tr("EDIT // BOUTON %02d", "EDIT // KNOB %02d").format(i + 1))

        d.section(tr("NOM", "NAME"))
        val name = tdrEdit(c, k.label, 14f, 14)
        d.row(name, 42)

        d.section(tr("ASSIGNATION", "ASSIGNMENT"))
        val chHint = Tdr.label(c, "→ " + Settings.channelLabel(k.channel), 10f, Tdr.CYAN)
        val ch = Stepper(c, tr("CANAL MIDI", "MIDI CHANNEL"), 1, 16, k.channel, editable = true,
            onChange = { chHint.text = "→ " + Settings.channelLabel(it) })
        d.row(ch)
        d.body.addView(chHint)
        val ccHint = Tdr.label(c, "→ " + CcNames.of(k.cc), 10f, Tdr.CYAN)
        val cc = Stepper(c, tr("COMMANDE CC", "CC NUMBER"), 0, 127, k.cc, editable = true,
            onChange = { ccHint.text = "→ " + CcNames.of(it) })
        d.row(cc)
        d.body.addView(ccHint)

        d.section(tr("PLAGE DE MODIFICATION", "RANGE"))
        val mn = Stepper(c, "MINIMUM", 0, 127, k.min, editable = true)
        val mx = Stepper(c, "MAXIMUM", 0, 127, k.max, editable = true)
        d.row(mn); d.row(mx)

        d.section(tr("ACCROCHE", "PICKUP"))
        d.note(tr("Valeur actuelle de l'instrument. Le fader ne modifie rien tant qu'il n'a pas rejoint cette valeur. " +
            "Si l'instrument renvoie ses CC, elle se met à jour toute seule.",
            "Current value on the instrument. The fader changes nothing until it reaches this value. " +
            "If the instrument sends its CCs back, it updates by itself."))
        val cur = Stepper(c, tr("VALEUR ACTUELLE", "CURRENT VALUE"), 0, 127, k.value, editable = true)
        d.row(cur)

        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("VALIDER", "OK"), primary = true) {
            k.label = name.text.toString()
            k.channel = ch.value
            k.cc = cc.value
            k.min = mn.value
            k.max = mx.value
            k.value = cur.value
            if (i / 32 == page) { binding = true; labelViews[i % 32].setText(k.label); binding = false }
            caught.remove(i)
            recheckCaught()
            Store.saveKnobs(act, knobs)
            refreshAll()
            d.dismiss()
        }
        d.show()
    }

    // ------------------------------------------------------------------ affichage
    fun refreshAll() {
        knobViews.forEach { kv ->
            val i = kv.index
            val sel = i in selected
            kv.chosen = sel
            kv.caught = i in caught
            kv.ghost = if (sel) mapped(knobs[i], fader.pos).coerceIn(0f, 127f) else -1f
            kv.invalidate()
        }
        btnEdit.active = editMode
        btnGroup.active = groupMode
        btnUndo.isEnabled = undoStack.isNotEmpty()
        btnUndo.invalidate()
        refreshInfo()
    }

    private fun refreshInfo() {
        if (!::info.isInitialized) return
        val sb = StringBuilder()
        sb.append(
            when {
                editMode -> tr("EDIT ▸ TOUCHE UN BOUTON\n", "EDIT ▸ TAP A KNOB\n")
                groupMode -> tr("GROUP ▸ TOUCHE POUR AJOUTER\n", "GROUP ▸ TAP TO ADD\n")
                else -> "MODE ▸ DIRECT\n"
            }
        )
        sb.append("SEL ▸ %02d   UNDO ▸ %02d\n".format(selected.size, undoStack.size))
        sb.append("────────────────\n")
        if (selected.isEmpty()) {
            sb.append(tr("Aucun bouton\nsélectionné.\n\nTouche un bouton\npuis bouge le fader.", "No knob\nselected.\n\nTap a knob,\nthen move the fader."))
        }
        for (i in selected.take(7)) {
            val k = knobs[i]
            val state = when {
                i in caught -> "●"
                k.value > mapped(k, fader.pos) -> "▲"
                else -> "▼"
            }
            val name = (if (k.label.isBlank()) "—" else k.label).take(9)
            sb.append("%03d %-9s\n   %s\n   CC%03d %03d %s\n".format(i + 1, name, Settings.channelLabel(k.channel).take(12), k.cc, k.value, state))
        }
        if (selected.size > 7) sb.append(tr("+ ${selected.size - 7} autres…", "+ ${selected.size - 7} more…"))
        info.text = sb.toString()
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }

    fun save() {
        Store.saveKnobs(act, knobs)
        Store.saveFader(act, fader.pos)
    }
}
