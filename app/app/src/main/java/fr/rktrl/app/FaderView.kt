package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * Fader vertical. Déplacement relatif : toucher le fader ne le fait jamais sauter,
 * il suit seulement le mouvement du doigt.
 * springBack = false (LATCH allumé) : il reste là où on le laisse.
 * springBack = true (LATCH éteint) : au relâchement, il glisse jusqu'au centre (valeurs envoyées en continu, sans saut).
 */
class FaderView(ctx: Context) : android.view.View(ctx) {

    var pos = 0f                // 0..1
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onStart: (() -> Unit)? = null
    var onMove: ((Float, Float) -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    var springBack = false
    private var touching = false
    private var anim: android.animation.ValueAnimator? = null
    private var startY = 0f
    private var startPos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun capH() = dp(38)
    private fun trackTop() = dp(40) + capH() / 2
    private fun trackBottom() = height - dp(20) - capH() / 2

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // on rattrape le fader pendant son retour au centre : le geste précédent est clos
                anim?.let { it.cancel(); anim = null; onEnd?.invoke() }
                touching = true
                startY = event.y
                startPos = pos
                onStart?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val len = trackBottom() - trackTop()
                if (len <= 0f) return true
                val np = (startPos - (event.y - startY) / len).coerceIn(0f, 1f)
                if (np != pos) {
                    val old = pos
                    pos = np
                    onMove?.invoke(old, np)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (springBack && kotlin.math.abs(pos - 0.5f) > 0.001f) glideToCenter() else onEnd?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Retour au centre en glissant : chaque image envoie la valeur intermédiaire (pas de saut). */
    private fun glideToCenter() {
        val from = pos
        val a = android.animation.ValueAnimator.ofFloat(from, 0.5f)
        a.duration = (180 + 520 * kotlin.math.abs(from - 0.5f) * 2).toLong()
        a.interpolator = android.view.animation.DecelerateInterpolator()
        a.addUpdateListener { va ->
            val np = va.animatedValue as Float
            if (np != pos) {
                val old = pos
                pos = np
                onMove?.invoke(old, np)
            }
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled && anim === a) { anim = null; onEnd?.invoke() }
            }
        })
        anim = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (Tdr.field) { drawField(canvas, w, h); return }
        val top = trackTop()
        val bot = trackBottom()
        val cx = w / 2

        // panneau
        Tdr.cutPath(path, dp(1), dp(1), w - dp(1), h - dp(1), dp(12))
        p.style = Paint.Style.FILL
        p.color = Tdr.PANEL
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = if (touching) Tdr.YELLOW else Tdr.GRID
        canvas.drawPath(path, p)

        // graduations
        p.strokeWidth = dp(1)
        for (t in 0..16) {
            val y = bot - (bot - top) * t / 16f
            val major = t % 4 == 0
            p.color = if (major) Tdr.WHITE else Tdr.DIM
            val len = if (major) w * 0.16f else w * 0.09f
            canvas.drawLine(w * 0.10f, y, w * 0.10f + len, y, p)
            canvas.drawLine(w * 0.90f - len, y, w * 0.90f, y, p)
        }

        // fente + remplissage
        p.style = Paint.Style.FILL
        p.color = Tdr.GRID
        canvas.drawRect(cx - dp(3), top, cx + dp(3), bot, p)
        val y = bot - (bot - top) * pos
        p.color = Tdr.YELLOW
        canvas.drawRect(cx - dp(3), y, cx + dp(3), bot, p)

        // curseur
        val cw = w * 0.62f
        val ch = capH()
        Tdr.cutPath(path, cx - cw / 2, y - ch / 2, cx + cw / 2, y + ch / 2, dp(8))
        p.color = if (touching) Tdr.WHITE else Tdr.YELLOW
        canvas.drawPath(path, p)
        p.color = Tdr.BG
        canvas.drawRect(cx - cw * 0.38f, y - dp(1.5f), cx + cw * 0.38f, y + dp(1.5f), p)
        canvas.drawRect(cx - cw * 0.25f, y - dp(9), cx + cw * 0.25f, y - dp(7.5f), p)
        canvas.drawRect(cx - cw * 0.25f, y + dp(7.5f), cx + cw * 0.25f, y + dp(9), p)

        // lecture
        p.typeface = Tdr.font
        p.textAlign = Paint.Align.CENTER
        p.textSize = dp(20)
        p.color = Tdr.YELLOW
        canvas.drawText("%03d".format((pos * 127).roundToInt()), cx, dp(28), p)
        p.typeface = Tdr.mono
        p.textSize = dp(8)
        p.color = Tdr.DIM
        canvas.drawText("FADER", cx, h - dp(8), p)
    }

    /** FIELD : cadre d'encre, fente fine, trait d'accent sous le curseur, curseur accent cerclé. */
    private fun drawField(canvas: Canvas, w: Float, h: Float) {
        val top = trackTop()
        val bot = trackBottom()
        val cx = w / 2
        Tdr.cutPath(path, dp(0.75f), dp(0.75f), w - dp(0.75f), h - dp(0.75f), dp(10))
        p.style = Paint.Style.FILL
        p.color = Tdr.BG
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = if (touching) Tdr.ACCENT else Tdr.WHITE
        canvas.drawPath(path, p)

        // graduations
        p.strokeWidth = dp(1)
        for (t in 0..16) {
            val y = bot - (bot - top) * t / 16f
            val major = t % 4 == 0
            p.color = if (major) Tdr.WHITE else Tdr.GRID
            val len = if (major) w * 0.14f else w * 0.08f
            canvas.drawLine(w * 0.12f, y, w * 0.12f + len, y, p)
            canvas.drawLine(w * 0.88f - len, y, w * 0.88f, y, p)
        }
        // fente + trait d'accent
        p.style = Paint.Style.FILL
        p.color = Tdr.WHITE
        canvas.drawRect(cx - dp(0.75f), top, cx + dp(0.75f), bot, p)
        val y = bot - (bot - top) * pos
        p.color = Tdr.ACCENT
        canvas.drawRect(cx - dp(2.5f), y, cx + dp(2.5f), bot, p)
        // curseur biseauté, comme le reste de l'interface
        val cw = w * 0.56f
        val ch = dp(22)
        Tdr.cutPath(path, cx - cw / 2, y - ch / 2, cx + cw / 2, y + ch / 2, dp(6))
        p.color = Tdr.ACCENT
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = Tdr.WHITE
        canvas.drawPath(path, p)
        canvas.drawLine(cx - cw * 0.28f, y, cx + cw * 0.28f, y, p)
        // valeur
        p.style = Paint.Style.FILL
        p.typeface = Tdr.font
        p.textAlign = Paint.Align.CENTER
        p.textSize = dp(15)
        p.color = Tdr.WHITE
        canvas.drawText("%03d".format((pos * 127).roundToInt()), cx, dp(26), p)
        p.typeface = Tdr.mono
        p.textSize = dp(8)
        p.color = Tdr.DIM
        canvas.drawText("FADER", cx, h - dp(8), p)
    }
}
