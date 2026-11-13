package fr.rktrl.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Champ texte sombre, typo condensée. */
fun tdrEdit(ctx: Context, initial: String, sp: Float = 14f, maxLen: Int = 24): EditText {
    val e = EditText(ctx)
    e.setText(initial)
    e.setSingleLine(true)
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    e.typeface = Tdr.font
    e.setTextColor(Tdr.WHITE)
    e.setHintTextColor(Tdr.DIM)
    e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
    e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    e.imeOptions = EditorInfo.IME_ACTION_DONE
    val bg = GradientDrawable()
    bg.setColor(Tdr.PANEL)
    bg.setStroke(ctx.dpi(1), Tdr.GRID)
    e.background = bg
    e.setPadding(ctx.dpi(8), ctx.dpi(2), ctx.dpi(8), ctx.dpi(2))
    return e
}

/**
 * Réglage numérique : libellé, bouton −, valeur, bouton +.
 * Maintenir − ou + fait défiler. En mode « editable », la valeur se tape aussi au clavier.
 */
class Stepper(
    ctx: Context,
    label: String,
    private val lo: Int,
    private val hi: Int,
    initial: Int,
    private val editable: Boolean = false,
    private val step: Int = 1,
    private val fmt: (Int) -> String = { it.toString() },
    private val onChange: ((Int) -> Unit)? = null,
    accent: Int = Tdr.YELLOW,
    compact: Boolean = false,
    valueColor: Int? = null
) : LinearLayout(ctx) {

    var value: Int = initial.coerceIn(lo, hi)
        private set

    private val valueView: TextView
    private val ticker = Handler(Looper.getMainLooper())
    private var typing = false

    init {
        // compact : libellé au-dessus, commandes en dessous (pour les colonnes étroites)
        val row: LinearLayout
        if (compact) {
            orientation = VERTICAL
            addView(Tdr.label(ctx, label, 9f, Tdr.DIM), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row = LinearLayout(ctx)
            row.orientation = HORIZONTAL
            val rlp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            rlp.topMargin = ctx.dpi(2)
            addView(row, rlp)
        } else {
            orientation = HORIZONTAL
            row = this
        }
        row.gravity = Gravity.CENTER_VERTICAL
        if (!compact) addView(Tdr.label(ctx, label, 11f, Tdr.DIM), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bw = ctx.dpi(if (compact) 32 else 40)
        row.addView(holdButton("−", -step, accent), LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))

        if (editable) {
            val e = tdrEdit(ctx, value.toString(), 15f, if (lo < 0) 5 else maxOf(3, hi.toString().length))
            e.inputType = if (lo < 0) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_NUMBER
            e.gravity = Gravity.CENTER
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (typing) return
                    val n = s?.toString()?.toIntOrNull() ?: return
                    val nv = n.coerceIn(lo, hi)
                    if (nv != value) {
                        value = nv
                        onChange?.invoke(nv)
                    }
                }
            })
            e.setOnFocusChangeListener { _, has -> if (!has) refresh() }
            valueView = e
        } else {
            valueView = Tdr.label(ctx, "", if (compact) 13f else 15f, Tdr.WHITE)
            valueView.gravity = Gravity.CENTER
            valueView.isSingleLine = true
            valueView.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val lp = if (compact) LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                 else LayoutParams(ctx.dpi(68), ViewGroup.LayoutParams.MATCH_PARENT)
        lp.leftMargin = ctx.dpi(4); lp.rightMargin = ctx.dpi(4)
        valueColor?.let { valueView.setTextColor(it) }
        row.addView(valueView, lp)
        row.addView(holdButton("+", step, accent), LayoutParams(bw, ViewGroup.LayoutParams.MATCH_PARENT))
        refresh()
    }

    /** Change la valeur (bornée). notify=false pour une mise à jour silencieuse. */
    fun update(v: Int, notify: Boolean = true) {
        val nv = v.coerceIn(lo, hi)
        val changed = nv != value
        value = nv
        refresh()
        if (changed && notify) onChange?.invoke(nv)
    }

    private fun refresh() {
        typing = true
        valueView.text = if (editable) value.toString() else fmt(value)
        typing = false
    }

    private fun holdButton(t: String, delta: Int, accent: Int): TdrButton {
        val b = TdrButton(context, t, accent)
        // maintien : défilement qui accélère (utile pour les grandes plages, ex. 1024 pas)
        var reps = 0
        val repeat = object : Runnable {
            override fun run() {
                reps++
                val mul = when {
                    hi - lo < 64 -> 1
                    reps > 40 -> 32
                    reps > 20 -> 8
                    else -> 1
                }
                update(value + delta * mul)
                ticker.postDelayed(this, 55)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reps = 0
                    v.isPressed = true
                    update(value + delta)
                    ticker.postDelayed(repeat, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ticker.removeCallbacks(repeat)
                }
            }
            true
        }
        return b
    }
}

/** Fenêtre de dialogue au style de l'appli. */
class TdrDialog(ctx: Context, title: String, private val accent: Int = Tdr.YELLOW) : Dialog(ctx) {

    val body: LinearLayout
    private val actions: LinearLayout

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.background = PanelDrawable(ctx, accent)
        root.setPadding(ctx.dpi(18), ctx.dpi(14), ctx.dpi(18), ctx.dpi(12))

        val head = Tdr.label(ctx, title, 15f, Tdr.BG)
        head.setBackgroundColor(accent)
        head.setPadding(ctx.dpi(10), ctx.dpi(5), ctx.dpi(14), ctx.dpi(5))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(ctx)
        body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, ctx.dpi(6), ctx.dpi(4), ctx.dpi(6))
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(44))
        alp.topMargin = ctx.dpi(8)
        root.addView(actions, alp)

        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun section(t: String) {
        val tv = Tdr.label(context, "▌ $t", 11f, accent)
        tv.setPadding(0, context.dpi(10), 0, context.dpi(4))
        body.addView(tv)
    }

    fun note(t: String, color: Int = Tdr.DIM): TextView {
        val tv = Tdr.label(context, t, 11f, color)
        tv.setPadding(0, context.dpi(2), 0, context.dpi(2))
        body.addView(tv)
        return tv
    }

    fun row(v: View, hDp: Int = 40) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(hDp))
        lp.topMargin = context.dpi(4)
        body.addView(v, lp)
    }

    fun button(t: String, a: Int = accent, f: () -> Unit): TdrButton {
        val b = TdrButton(context, t, a).onClick(f)
        row(b, 40)
        return b
    }

    /** Bouton d'action en bas de la fenêtre ; primary = action principale, pleine en couleur d'accent. */
    fun action(t: String, a: Int = accent, primary: Boolean = false, f: () -> Unit) {
        val lp = LinearLayout.LayoutParams(context.dpi(128), ViewGroup.LayoutParams.MATCH_PARENT)
        lp.leftMargin = context.dpi(8)
        val b = TdrButton(context, t, if (primary) Tdr.ACCENT else a).onClick(f)
        b.active = primary
        actions.addView(b, lp)
    }


    override fun show() {
        super.show()
        val dm = context.resources.displayMetrics
        window?.setLayout((dm.widthPixels * 0.72f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/** Grille de choix exclusifs (notes, gammes…). */
fun choiceGrid(
    ctx: Context,
    items: Array<String>,
    cols: Int,
    selected: Int,
    accent: Int,
    onPick: (Int) -> Unit
): LinearLayout {
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    val buttons = ArrayList<TdrButton>()
    var row: LinearLayout? = null
    items.forEachIndexed { i, s ->
        if (i % cols == 0) {
            row = LinearLayout(ctx).also {
                it.orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36))
                lp.topMargin = ctx.dpi(4)
                box.addView(it, lp)
            }
        }
        val b = TdrButton(ctx, s, accent)
        b.active = i == selected
        b.onClick {
            buttons.forEach { it.active = false }
            b.active = true
            onPick(i)
        }
        buttons.add(b)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.rightMargin = ctx.dpi(4)
        row?.addView(b, lp)
    }
    // compléter la dernière ligne pour garder des largeurs égales
    val rest = (cols - items.size % cols) % cols
    repeat(rest) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.rightMargin = ctx.dpi(4)
        row?.addView(View(ctx), lp)
    }
    return box
}

/** Petit sélecteur de couleur : nuancier + code hexadécimal. */
fun pickColor(ctx: Context, title: String, initial: Int, onPick: (Int) -> Unit) {
    val d = TdrDialog(ctx, title)
    val swatches = intArrayOf(
        0xFF000000.toInt(), 0xFF101010.toInt(), 0xFF1E1E24.toInt(), 0xFF2B2B33.toInt(), 0xFF5A5A66.toInt(), 0xFF9A9AA6.toInt(),
        0xFFE8E8E8.toInt(), 0xFFFFFFFF.toInt(), 0xFFF3EBDD.toInt(), 0xFFFFE100.toInt(), 0xFFFFB12B.toInt(), 0xFFFF6A00.toInt(),
        0xFFFF2A2A.toInt(), 0xFFFF2D87.toInt(), 0xFFB026FF.toInt(), 0xFF5C8DFF.toInt(), 0xFF00D8FF.toInt(), 0xFF00FF9F.toInt(),
        0xFF7CFF4F.toInt(), 0xFF0B3D2E.toInt(), 0xFF0A1F44.toInt(), 0xFF3A0A1E.toInt(), 0xFF3B2A12.toInt(), 0xFF2A123B.toInt()
    )
    var chosen = initial
    val hex = tdrEdit(ctx, "%06X".format(initial and 0xFFFFFF), 15f, 6)
    val preview = View(ctx)
    preview.setBackgroundColor(initial)
    val cols = 6
    var row: LinearLayout? = null
    swatches.forEachIndexed { i, col ->
        if (i % cols == 0) {
            row = LinearLayout(ctx).also {
                it.orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(34))
                lp.topMargin = ctx.dpi(4)
                d.body.addView(it, lp)
            }
        }
        val sw = View(ctx)
        val bg = GradientDrawable()
        bg.setColor(col)
        bg.setStroke(ctx.dpi(1), 0xFF808080.toInt())
        sw.background = bg
        sw.setOnClickListener {
            chosen = col
            preview.setBackgroundColor(col)
            hex.setText("%06X".format(col and 0xFFFFFF))
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.rightMargin = ctx.dpi(4)
        row?.addView(sw, lp)
    }
    d.section(tr("CODE COULEUR (HEX)", "COLOUR CODE (HEX)"))
    val line = LinearLayout(ctx)
    line.orientation = LinearLayout.HORIZONTAL
    line.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    val plp = LinearLayout.LayoutParams(ctx.dpi(60), ViewGroup.LayoutParams.MATCH_PARENT)
    plp.leftMargin = ctx.dpi(8)
    line.addView(preview, plp)
    d.row(line, 40)
    hex.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val t = s?.toString()?.trim()?.removePrefix("#") ?: return
            if (t.length == 6) t.toIntOrNull(16)?.let { chosen = 0xFF000000.toInt() or it; preview.setBackgroundColor(chosen) }
        }
    })
    d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
    d.action("OK", primary = true) { onPick(chosen); d.dismiss() }
    d.show()
}
