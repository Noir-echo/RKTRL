package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent

/**
 * Bandeau du haut : logo (touché = menu OPTIONS), numéro de page,
 * état de la liaison MIDI (touché = connexion).
 */
class HeaderBar(ctx: Context, private val code: String, private val title: String) : android.view.View(ctx) {

    var status: String = "NO LINK"
        set(v) { field = v; invalidate() }
    var linked: Boolean = false
        set(v) { field = v; invalidate() }
    var running: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var onLogo: (() -> Unit)? = null
    var onLink: (() -> Unit)? = null
    /** FIELD : onglets de pages cliquables (0 = controller, 1 = sequencer, 2 = destroy). */
    var onTab: ((Int) -> Unit)? = null
    /** FIELD : tempo affiché. */
    var bpm: (() -> Int)? = null
    var deviceName: String? = null
        set(v) { field = v; invalidate() }
    private val tabL = FloatArray(3)
    private val tabR = FloatArray(3)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var downX = 0f

    init {
        isClickable = true
        setOnClickListener {
            if (downX < logoWidth()) { onLogo?.invoke(); return@setOnClickListener }
            if (Tdr.field) for (i in 0..2) if (downX >= tabL[i] && downX <= tabR[i]) { onTab?.invoke(i); return@setOnClickListener }
            onLink?.invoke()
        }
    }

    private fun logoWidth() = if (Tdr.field) height * 3.4f else height * 5.2f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.x
        return super.onTouchEvent(event)
    }

    /** FIELD : logo italique, onglets « ctrl / 01 », tempo, liaison ; filet noir en bas. */
    private fun drawField(canvas: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.color = Tdr.BG
        canvas.drawRect(0f, 0f, w, h, p)
        val base = h / 2
        p.typeface = Tdr.logo
        p.textAlign = Paint.Align.LEFT
        p.textSize = h * 0.5f
        p.color = if (isPressed && downX < logoWidth()) Tdr.ACCENT else Tdr.WHITE
        val ty = base - (p.descent() + p.ascent()) / 2
        canvas.drawText("RKTRL", dp(16), ty, p)
        // onglets
        val pageIdx = (code.toIntOrNull() ?: 1) - 1
        val tabs = arrayOf("ctrl / 01", "seq / 02", "dstr / 03")
        p.typeface = Tdr.mono
        p.textSize = h * 0.28f
        var x = logoWidth() + dp(4)
        val tyy = base - (p.descent() + p.ascent()) / 2
        for (i in 0..2) {
            val tw = p.measureText(tabs[i])
            tabL[i] = x - dp(6); tabR[i] = x + tw + dp(6)
            p.color = if (i == pageIdx) Tdr.WHITE else Tdr.DIM
            canvas.drawText(tabs[i], x, tyy, p)
            if (i == pageIdx) canvas.drawRect(x, h - dp(6), x + tw, h - dp(4), p)
            x += tw + dp(22)
        }
        // à droite : liaison puis tempo
        p.textAlign = Paint.Align.RIGHT
        val dev = deviceName?.lowercase() ?: tr("pas de liaison", "no link")
        var rx = w - dp(16)
        // rien de connecté : « pas de liaison » clignote en couleur d'accent
        val blinkOn = linked || (System.currentTimeMillis() / 600) % 2L == 0L
        p.color = if (linked) Tdr.WHITE else if (blinkOn) Tdr.ACCENT else Tdr.BG
        canvas.drawText(dev, rx, tyy, p)
        if (!linked && isShown) postInvalidateDelayed(300)
        rx -= p.measureText(dev) + dp(8)
        val sq = h * 0.22f
        p.color = if (linked || blinkOn) Tdr.ACCENT else Tdr.BG
        canvas.drawRect(rx - sq, base - sq / 2, rx, base + sq / 2, p)
        rx -= sq + dp(18)
        val b = bpm?.invoke()
        if (b != null) {
            p.color = Tdr.WHITE
            val t = "♩ $b"
            canvas.drawText(t, rx, tyy, p)
            rx -= p.measureText(t) + dp(8)
        }
        if (running) {
            p.color = Tdr.ACCENT
            canvas.drawText("▶", rx, tyy, p)
        }
        p.color = Tdr.WHITE
        canvas.drawRect(0f, h - dp(1.5f), w, h, p)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && Tdr.field) { drawField(canvas, w, h); return }
        if (w <= 0f) return

        // bloc logo RKTRL (bouton OPTIONS) : parallélogramme, lettres penchées, chevrons de vitesse
        val lw = logoWidth()
        path.reset()
        path.moveTo(0f, 0f); path.lineTo(lw, 0f); path.lineTo(lw - h * 0.6f, h); path.lineTo(0f, h); path.close()
        p.style = Paint.Style.FILL
        p.color = if (isPressed && downX < lw) Tdr.WHITE else Tdr.YELLOW
        canvas.drawPath(path, p)

        p.typeface = Tdr.font
        p.textAlign = Paint.Align.LEFT
        p.textSize = h * 0.62f
        p.textSkewX = -0.28f
        p.isFakeBoldText = true
        p.color = Tdr.BG
        val base = h / 2 - (p.descent() + p.ascent()) / 2
        canvas.drawText("RKTRL", dp(12), base, p)
        val tw0 = p.measureText("RKTRL")
        p.textSkewX = 0f
        p.isFakeBoldText = false
        // chevrons « >> » après le nom
        var cxv = dp(12) + tw0 + dp(6)
        for (k in 0..1) {
            path.reset()
            path.moveTo(cxv, h * 0.22f); path.lineTo(cxv + h * 0.16f, h * 0.22f)
            path.lineTo(cxv + h * 0.40f, h * 0.5f); path.lineTo(cxv + h * 0.16f, h * 0.78f)
            path.lineTo(cxv, h * 0.78f); path.lineTo(cxv + h * 0.24f, h * 0.5f); path.close()
            canvas.drawPath(path, p)
            cxv += h * 0.26f
        }
        // petit repère « menu »
        val mx = lw - h * 0.95f
        for (i in 0..2) canvas.drawRect(mx, h * (0.32f + i * 0.15f), mx + h * 0.24f, h * (0.32f + i * 0.15f) + dp(1.6f), p)

        // hachures
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2)
        p.color = Tdr.YELLOW
        var x = lw + dp(4)
        repeat(5) {
            canvas.drawLine(x, h, x + h * 0.6f, 0f, p)
            x += dp(7)
        }
        x += h * 0.6f + dp(8)

        // numéro + nom de page
        p.style = Paint.Style.FILL
        p.color = Tdr.WHITE
        p.textSize = h * 0.62f
        canvas.drawText(code, x, base, p)
        x += p.measureText(code) + dp(6)
        p.textSize = h * 0.30f
        p.color = Tdr.YELLOW
        canvas.drawText(title, x, h * 0.48f, p)
        p.typeface = Tdr.mono
        p.textSize = h * 0.22f
        p.color = Tdr.DIM
        canvas.drawText("REKONTROL // MIDI CONTROL SYSTEM", x, h * 0.84f, p)

        // état de la liaison, à droite
        p.typeface = Tdr.font
        p.textSize = h * 0.38f
        val st = (if (linked) "◉ " else "○ ") + status
        val tw = p.measureText(st) + dp(28)
        val rx = w - tw
        path.reset()
        path.moveTo(rx + h * 0.5f, dp(3)); path.lineTo(w - dp(2), dp(3))
        path.lineTo(w - dp(2), h - dp(3)); path.lineTo(rx, h - dp(3)); path.close()
        val col = if (linked) Tdr.CYAN else Tdr.MAGENTA
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = col
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        canvas.drawText(st, rx + dp(18), base, p)

        if (running) {
            val rs = "▶ SEQ RUN"
            p.color = Tdr.GREEN
            canvas.drawText(rs, rx - p.measureText(rs) - dp(14), base, p)
        }

        p.color = Tdr.GRID
        canvas.drawRect(0f, h - dp(1), w, h, p)
    }
}
