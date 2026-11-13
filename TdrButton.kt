package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min

/**
 * Bouton rectangulaire à angles coupés. Plein quand actif ou pressé.
 * hazard = true : bouton « danger » à rayures jaunes et noires (KILL).
 */
class TdrButton(
    ctx: Context,
    text: String,
    accent: Int = Tdr.YELLOW,
    code: String = ""
) : android.view.View(ctx) {

    var text: String = text
        set(v) { field = v; invalidate() }
    var accent: Int = accent
        set(v) { field = v; invalidate() }
    var code: String = code
        set(v) { field = v; invalidate() }
    var active: Boolean = false
        set(v) { field = v; invalidate() }
    /** Toujours écrire en alphabet latin (ex. choix de la langue). */
    var latin: Boolean = false
        set(v) { field = v; invalidate() }
    var hazard: Boolean = false
        set(v) { field = v; invalidate() }
    /** Bouton « d'urgence » : rouge, encore plus vif quand il est actif. */
    var emergency: Boolean = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        isClickable = true
        isFocusable = false
    }

    fun onClick(f: () -> Unit): TdrButton {
        setOnClickListener { f() }
        return this
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (hazard) { drawHazard(canvas, w, h); return }
        if (emergency && !Tdr.field) { drawEmergency(canvas, w, h); return }

        val sw = dp(Tdr.strokeDp)
        val cut = min(h * 0.32f, dp(14))
        Tdr.cutPath(path, sw / 2, sw / 2, w - sw / 2, h - sw / 2, cut)

        val lit = active || isPressed
        val a = if (isEnabled) accent else Tdr.DIM

        p.style = Paint.Style.FILL
        p.color = if (lit) a else Tdr.PANEL
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = a
        canvas.drawPath(path, p)

        // FIELD : un bouton plein en accent garde un texte à l'encre (comme les maquettes)
        val litText = if (Tdr.field && a == Tdr.ACCENT) Tdr.WHITE else Tdr.BG
        drawLabel(canvas, w, h, if (lit) litText else Tdr.WHITE, w * 0.86f)

        if (code.isNotEmpty() && h > dp(34)) {
            p.typeface = if (latin) Tdr.latin else Tdr.mono
            p.textAlign = Paint.Align.LEFT
            p.textSize = dp(7.5f)
            p.color = if (lit) Tdr.BG else a
            canvas.drawText(code, cut + dp(3), dp(10), p)
        }
    }

    private fun drawLabel(canvas: Canvas, w: Float, h: Float, color: Int, maxW: Float) {
        p.style = Paint.Style.FILL
        p.typeface = if (latin) Tdr.latin else Tdr.font
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(h * (if (Tdr.field) 0.30f else 0.38f), dp(if (Tdr.field) 14 else 18))
        while (p.measureText(text) > maxW && p.textSize > dp(7)) p.textSize -= dp(0.5f)
        p.color = color
        val base = h / 2 - (p.descent() + p.ascent()) / 2
        // FIELD : le symbole ▶ / ■ prend la couleur d'accent
        val first = text.firstOrNull()
        if (Tdr.field && (first == '▶' || first == '■') && text.length > 1) {
            val total = p.measureText(text)
            val left = w / 2 - total / 2
            p.textAlign = Paint.Align.LEFT
            p.color = Tdr.ACCENT
            canvas.drawText(first.toString(), left, base, p)
            p.color = color
            canvas.drawText(text.substring(1), left + p.measureText(first.toString()), base, p)
            return
        }
        canvas.drawText(text, w / 2, base, p)
    }

    /** Bouton d'urgence rouge : sombre au repos, rouge vif + double cadre quand actif. */
    private fun drawEmergency(canvas: Canvas, w: Float, h: Float) {
        val cut = min(h * 0.32f, dp(14))
        val sw = dp(2)
        val lit = active || isPressed
        Tdr.cutPath(path, sw / 2, sw / 2, w - sw / 2, h - sw / 2, cut)
        p.style = Paint.Style.FILL
        p.color = if (lit) Tdr.RED else Tdr.mix(Tdr.BG, Tdr.RED, 0.28f)
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = Tdr.RED
        canvas.drawPath(path, p)
        if (lit) {
            // second cadre intérieur, effet « voyant allumé »
            val i = dp(4)
            Tdr.cutPath(path, i, i, w - i, h - i, (cut - i / 2).coerceAtLeast(dp(2)))
            p.strokeWidth = dp(1.5f)
            p.color = Tdr.WHITE
            canvas.drawPath(path, p)
        }
        drawLabel(canvas, w, h, Tdr.WHITE, w * 0.8f)
    }

    /** Rayures de danger + étiquette centrale. Armé (active) = rouge. */
    private fun drawHazard(canvas: Canvas, w: Float, h: Float) {
        if (Tdr.field) { drawHazardField(canvas, w, h); return }
        val cut = min(h * 0.32f, dp(14))
        Tdr.cutPath(path, 0f, 0f, w, h, cut)
        canvas.save()
        canvas.clipPath(path)
        p.style = Paint.Style.FILL
        p.color = if (active) Tdr.RED else Tdr.YELLOW
        canvas.drawRect(0f, 0f, w, h, p)
        p.color = Tdr.BG
        val band = dp(9)
        var x = -h
        val stripe = Path()
        while (x < w + h) {
            stripe.reset()
            stripe.moveTo(x, h); stripe.lineTo(x + band, h); stripe.lineTo(x + band + h, 0f); stripe.lineTo(x + h, 0f)
            stripe.close()
            canvas.drawPath(stripe, p)
            x += band * 2
        }
        // plaque centrale
        val inset = dp(6)
        val pressed = isPressed
        p.color = if (pressed) Tdr.WHITE else Tdr.BG
        canvas.drawRect(inset * 2.2f, inset, w - inset * 2.2f, h - inset, p)
        canvas.restore()
        drawLabel(canvas, w, h, if (pressed) Tdr.BG else if (active) Tdr.RED else Tdr.YELLOW, w - inset * 6)
    }

    /** FIELD : rayures accent / papier, sans contour, plaque centrale papier. */
    private fun drawHazardField(canvas: Canvas, w: Float, h: Float) {
        val cut = min(h * 0.32f, dp(14))
        Tdr.cutPath(path, 0f, 0f, w, h, cut)
        canvas.save()
        canvas.clipPath(path)
        p.style = Paint.Style.FILL
        p.color = Tdr.ACCENT
        canvas.drawRect(0f, 0f, w, h, p)
        p.color = Tdr.BG
        val band = dp(6)
        var x = -h
        val stripe = Path()
        while (x < w + h) {
            stripe.reset()
            stripe.moveTo(x, h); stripe.lineTo(x + band, h); stripe.lineTo(x + band + h, 0f); stripe.lineTo(x + h, 0f)
            stripe.close()
            canvas.drawPath(stripe, p)
            x += band * 2
        }
        val inset = dp(6)
        val pressed = isPressed
        p.color = when { pressed -> Tdr.WHITE; active -> Tdr.ACCENT; else -> Tdr.BG }
        canvas.drawRect(inset * 1.8f, inset, w - inset * 1.8f, h - inset, p)
        canvas.restore()
        drawLabel(canvas, w, h, if (pressed) Tdr.BG else Tdr.WHITE, w - inset * 5)
    }
}
