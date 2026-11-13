package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Bouton rond assignable.
 * - anneau : valeur actuelle de l'instrument (0–127), trait fin = plage du fader
 * - trait magenta : position du fader (où il « est » pour ce bouton)
 * - flèche magenta : sens dans lequel bouger le fader pour accrocher la valeur
 * - point vert : valeur accrochée, le fader pilote le paramètre
 */
class KnobView(ctx: Context, var index: Int) : android.view.View(ctx) {

    var cfg: KnobConfig? = null
    var chosen = false
    var caught = false
    var ghost = -1f
    var onTap: ((Int) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val arc2 = RectF()
    private val path = Path()

    init {
        isClickable = true
        setOnClickListener { onTap?.invoke(index) }
    }

    private fun ang(v: Float) = 135f + 270f * v / 127f

    /** FIELD : piste grise, courbe d'encre (accent si sélectionné), valeur au centre, numéro en haut à gauche. */
    private fun drawField(canvas: Canvas, k: KnobConfig) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f - dp(3)
        if (r < dp(8)) return
        val cx = w / 2
        val cy = h / 2
        val ar = r - dp(2)
        arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = dp(1.5f)
        p.color = Tdr.GRID
        canvas.drawArc(arc, 135f, 270f, false, p)
        p.strokeWidth = max(dp(3), r * 0.11f)
        p.color = if (chosen) Tdr.ACCENT else Tdr.WHITE
        if (k.value > 0) canvas.drawArc(arc, 135f, 270f * k.value / 127f, false, p)

        // repère de la position du fader
        if (ghost >= 0f) {
            val a = Math.toRadians(ang(ghost).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            p.strokeWidth = dp(2)
            p.color = Tdr.WHITE
            canvas.drawLine(cx + ca * (ar - dp(5)), cy + sa * (ar - dp(5)), cx + ca * (ar + dp(3)), cy + sa * (ar + dp(3)), p)
        }

        val dr = ar * 0.74f
        p.style = Paint.Style.FILL
        p.color = if (chosen) Tdr.ACCENT else Tdr.BG
        canvas.drawCircle(cx, cy, dr, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = Tdr.WHITE
        canvas.drawCircle(cx, cy, dr, p)

        p.style = Paint.Style.FILL
        p.typeface = Tdr.font
        p.textAlign = Paint.Align.CENTER
        p.textSize = dr * 0.62f
        p.color = Tdr.WHITE
        canvas.drawText("%03d".format(k.value), cx, cy - (p.descent() + p.ascent()) / 2, p)

        p.typeface = Tdr.mono
        p.textAlign = Paint.Align.LEFT
        p.textSize = dp(7.5f)
        p.color = Tdr.DIM
        canvas.drawText("%03d".format(index + 1), cx - r - dp(1), cy - r + dp(6), p)

        // accroche : point = accroché, flèche = sens où bouger le fader
        if (chosen) {
            val ix = cx + r - dp(3)
            val iy = cy - r + dp(4)
            p.color = Tdr.WHITE
            if (caught) canvas.drawRect(ix - dp(3), iy - dp(3), ix + dp(3), iy + dp(3), p)
            else if (ghost >= 0f) {
                val sz = dp(4.5f)
                path.reset()
                if (k.value > ghost) { path.moveTo(ix, iy - sz); path.lineTo(ix + sz, iy + sz * 0.7f); path.lineTo(ix - sz, iy + sz * 0.7f) }
                else { path.moveTo(ix, iy + sz); path.lineTo(ix + sz, iy - sz * 0.7f); path.lineTo(ix - sz, iy - sz * 0.7f) }
                path.close()
                canvas.drawPath(path, p)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val k = cfg ?: return
        if (Tdr.field) { drawField(canvas, k); return }
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f - dp(3)
        if (r < dp(8)) return
        val cx = w / 2
        val cy = h / 2
        val ring = max(dp(3), r * 0.13f)
        val ar = r - ring / 2
        arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
        val accent = if (chosen) Tdr.YELLOW else Tdr.CYAN

        // piste
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = ring
        p.color = Tdr.GRID
        canvas.drawArc(arc, 135f, 270f, false, p)

        // plage min–max
        val lo = min(k.min, k.max).toFloat()
        val hi = max(k.min, k.max).toFloat()
        val ar2 = ar - ring * 1.1f
        arc2.set(cx - ar2, cy - ar2, cx + ar2, cy + ar2)
        p.strokeWidth = max(dp(1), ring * 0.3f)
        p.color = Tdr.DIM
        canvas.drawArc(arc2, ang(lo), max(1f, ang(hi) - ang(lo)), false, p)

        // valeur
        p.strokeWidth = ring
        p.color = accent
        if (k.value > 0) canvas.drawArc(arc, 135f, 270f * k.value / 127f, false, p)

        // position du fader
        if (ghost >= 0f) {
            val a = Math.toRadians(ang(ghost).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            p.color = Tdr.MAGENTA
            p.strokeWidth = dp(2.5f)
            canvas.drawLine(cx + ca * (r - ring * 1.7f), cy + sa * (r - ring * 1.7f), cx + ca * (r + dp(1)), cy + sa * (r + dp(1)), p)
        }

        // disque central
        val dr = r - ring * 2.2f
        p.style = Paint.Style.FILL
        p.color = if (chosen) Tdr.YELLOW else Tdr.PANEL2
        canvas.drawCircle(cx, cy, dr, p)

        p.typeface = Tdr.font
        p.textAlign = Paint.Align.CENTER
        p.textSize = dr * 0.8f
        p.color = if (chosen) Tdr.BG else Tdr.WHITE
        canvas.drawText(k.value.toString(), cx, cy + p.textSize * 0.30f, p)

        p.typeface = Tdr.mono
        p.textSize = max(dp(6), dr * 0.27f)
        p.color = if (chosen) Tdr.BG else Tdr.DIM
        canvas.drawText("${k.channel}/${k.cc}", cx, cy + dr * 0.72f, p)

        // numéro du bouton
        p.textAlign = Paint.Align.LEFT
        p.textSize = dp(8)
        p.color = if (chosen) Tdr.YELLOW else Tdr.DIM
        canvas.drawText("%03d".format(index + 1), cx - r, cy - r + dp(7), p)

        // état d'accroche
        if (chosen) {
            val ix = cx + r - dp(4)
            val iy = cy - r + dp(4)
            if (caught) {
                p.color = Tdr.GREEN
                canvas.drawCircle(ix, iy, dp(3.5f), p)
            } else if (ghost >= 0f) {
                val s = dp(5)
                path.reset()
                if (k.value > ghost) {          // il faut monter le fader
                    path.moveTo(ix, iy - s); path.lineTo(ix + s, iy + s * 0.7f); path.lineTo(ix - s, iy + s * 0.7f)
                } else {                         // il faut descendre
                    path.moveTo(ix, iy + s); path.lineTo(ix + s, iy - s * 0.7f); path.lineTo(ix - s, iy - s * 0.7f)
                }
                path.close()
                p.color = Tdr.MAGENTA
                canvas.drawPath(path, p)
            }
        }
    }
}
