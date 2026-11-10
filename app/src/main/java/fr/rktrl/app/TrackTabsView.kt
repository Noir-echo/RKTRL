package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent

/**
 * Colonne de 16 cases (2 x 8) à gauche de l'écran — pistes du séquenceur ou canaux du Destroy Mode.
 * - toucher : action principale (choisir la piste / activer le canal)
 * - appui long : action secondaire (MUTE de la piste)
 */
class TrackTabsView(
    ctx: Context,
    private val label: (Int) -> String,
    private val sub: (Int) -> String,
    private val isOn: (Int) -> Boolean,          // case « allumée »
    private val isDim: (Int) -> Boolean,         // case grisée (muette)
    private val hasContent: (Int) -> Boolean
) : android.view.View(ctx) {

    var onTap: ((Int) -> Unit)? = null
    var onLongPress: ((Int) -> Unit)? = null
    /** Clignotement (pistes qui jouent). */
    var flashing: ((Int) -> Boolean)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(indexAt(e.x, e.y)); invalidate(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke(indexAt(e.x, e.y)); invalidate()
        }
    })

    private fun indexAt(x: Float, y: Float): Int {
        val col = (x / (width / 2f)).toInt().coerceIn(0, 1)
        val row = (y / (height / 8f)).toInt().coerceIn(0, 7)
        return col * 8 + row
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val cw = w / 2f
        val ch = h / 8f
        for (i in 0 until TRACKS) {
            val col = i / 8
            val row = i % 8
            val l = col * cw + dp(1.5f)
            val r = (col + 1) * cw - dp(1.5f)
            val t = row * ch + dp(1.5f)
            val b = (row + 1) * ch - dp(1.5f)
            val c = Tdr.trackColor(i)
            val on = isOn(i)
            val dim = isDim(i)
            Tdr.cutPath(path, l, t, r, b, dp(6))
            p.style = Paint.Style.FILL
            p.color = when {
                on -> c
                flashing?.invoke(i) == true -> Tdr.PANEL2
                else -> Tdr.PANEL
            }
            canvas.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1.5f)
            p.color = if (dim) Tdr.DIM else c
            canvas.drawPath(path, p)

            // numéro + nom
            p.style = Paint.Style.FILL
            p.typeface = Tdr.mono
            p.textAlign = Paint.Align.LEFT
            p.textSize = minOf(dp(8), ch * 0.3f)
            p.color = if (on) Tdr.BG else if (dim) Tdr.DIM else c
            canvas.drawText(sub(i), l + dp(5), t + p.textSize + dp(2), p)

            p.typeface = Tdr.font
            p.textSize = minOf(dp(12), ch * 0.40f)
            var txt = label(i)
            while (txt.length > 1 && p.measureText(txt) > (r - l) - dp(10)) txt = txt.dropLast(1)
            p.color = if (on) Tdr.BG else if (dim) Tdr.DIM else Tdr.WHITE
            canvas.drawText(txt, l + dp(5), b - dp(5), p)

            if (hasContent(i)) {
                p.color = if (on) Tdr.BG else c
                canvas.drawRect(r - dp(8), t + dp(4), r - dp(4), t + dp(8), p)
            }
            if (dim) {
                p.textAlign = Paint.Align.RIGHT
                p.typeface = Tdr.mono
                p.textSize = dp(8)
                p.color = if (on) Tdr.BG else Tdr.MAGENTA
                canvas.drawText("M", r - dp(10), t + dp(9), p)
            }
        }
    }
}
