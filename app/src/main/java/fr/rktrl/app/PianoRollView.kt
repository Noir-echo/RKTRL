package fr.rktrl.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent

/**
 * Piano roll vertical (une octave + le do du dessus) pour choisir les notes à poser sur les pas.
 * - sans HOLD : toucher une touche la choisit seule (re-toucher = plus rien de choisi) ;
 *   plusieurs doigts en même temps = plusieurs notes ;
 * - avec HOLD : chaque toucher ajoute / retire la note, sans avoir à appuyer à plusieurs doigts.
 * Chaque toucher fait entendre la note.
 */
@SuppressLint("ViewConstructor")
class PianoRollView(ctx: Context) : android.view.View(ctx) {

    /** Note la plus grave affichée (toujours un do). */
    var base = 48
        set(v) { field = v.coerceIn(0, 115); invalidate() }
    var hold = false
    val selection = sortedSetOf<Int>()
    var onChange: (() -> Unit)? = null
    var onPreview: ((note: Int, on: Boolean) -> Unit)? = null
    /** Notes du pas en cours de lecture (repère discret). */
    var playing: (() -> Collection<Int>)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val held = HashMap<Int, Int>()          // doigt → note jouée
    private val rows = 13

    private fun isBlack(n: Int) = Math.floorMod(n, 12) in intArrayOf(1, 3, 6, 8, 10)

    private fun noteAt(y: Float): Int {
        val rh = height / rows.toFloat()
        val r = ((height - y) / rh).toInt().coerceIn(0, rows - 1)
        return base + r
    }

    fun clear() { selection.clear(); invalidate(); onChange?.invoke() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = e.actionIndex
                val n = noteAt(e.getY(i))
                val first = e.actionMasked == MotionEvent.ACTION_DOWN
                when {
                    hold -> if (!selection.remove(n)) selection.add(n)
                    first -> {
                        val only = selection.size == 1 && selection.first() == n
                        selection.clear()
                        if (!only) selection.add(n)
                    }
                    else -> selection.add(n)            // accord à plusieurs doigts
                }
                if (selection.contains(n)) {
                    held[e.getPointerId(i)] = n
                    onPreview?.invoke(n, true)
                }
                onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                held.remove(e.getPointerId(e.actionIndex))?.let { onPreview?.invoke(it, false) }
            }
            MotionEvent.ACTION_CANCEL -> {
                held.values.forEach { onPreview?.invoke(it, false) }
                held.clear()
            }
        }
        invalidate()
        return true
    }

    fun releaseAll() { held.values.forEach { onPreview?.invoke(it, false) }; held.clear() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rh = h / rows
        val sw = dp(Tdr.strokeDp)
        val play = playing?.invoke() ?: emptyList()
        for (r in 0 until rows) {
            val n = base + r
            val b = h - r * rh
            val t = b - rh
            val sel = n in selection
            val black = isBlack(n)
            // touche blanche : bande papier ; touche noire : barre d'encre de 62 % de large
            p.style = Paint.Style.FILL
            p.color = if (sel && !black) Tdr.ACCENT else Tdr.PANEL
            canvas.drawRect(sw / 2, t, w - sw / 2, b, p)
            if (black) {
                p.color = if (sel) Tdr.ACCENT else Tdr.WHITE
                canvas.drawRect(sw / 2, t + dp(1), w * 0.62f, b - dp(1), p)
            }
            // séparation entre touches blanches (plus marquée sous chaque do)
            p.color = if (Math.floorMod(n, 12) == 0) Tdr.WHITE else Tdr.GRID
            val lw = if (Math.floorMod(n, 12) == 0) sw else dp(1)
            if (!black && !isBlack(n - 1)) canvas.drawRect(0f, b - lw / 2, w, b + lw / 2, p)
            // repère de lecture
            if (n in play) {
                p.color = if (sel) Tdr.WHITE else Tdr.ACCENT
                canvas.drawCircle(w - dp(8), (t + b) / 2, dp(3), p)
            }
            // nom de la note
            p.typeface = Tdr.mono
            p.textAlign = Paint.Align.RIGHT
            p.textSize = minOf(dp(8.5f), rh * 0.55f)
            val c = Math.floorMod(n, 12) == 0
            if (!black) {
                p.color = if (sel) Tdr.WHITE else if (c) Tdr.WHITE else Tdr.DIM
                p.isFakeBoldText = c
                canvas.drawText(Tdr.noteName(n), w - dp(if (n in play) 14 else 5), (t + b) / 2 - (p.ascent() + p.descent()) / 2, p)
                p.isFakeBoldText = false
            }
        }
        // cadre
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = Tdr.WHITE
        canvas.drawRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, p)
    }
}
