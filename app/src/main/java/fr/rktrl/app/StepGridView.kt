package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StepParam { NOTE, VEL, DUR, CHORD, COND, RATCH }

/**
 * Les 32 pas d'une piste, disposés comme les 32 encodeurs du BCR (4 rangées de 8).
 * - toucher un pas : active / coupe le pas
 * - glisser verticalement sur un pas : modifie le paramètre affiché
 * - les pas couverts par une note longue portent une barre de liaison
 */
class StepGridView(ctx: Context) : android.view.View(ctx) {

    var track: SeqTrack? = null
        set(v) { field = v; invalidate() }
    var param = StepParam.NOTE
        set(v) { field = v; invalidate() }
    var accent = Tdr.YELLOW
        set(v) { field = v; invalidate() }
    var onEdited: ((Int) -> Unit)? = null
    /** Appui long sur un pas : mode avancé (expression). */
    var onLongPress: ((Int) -> Unit)? = null
    private var longFired = false
    private val longPress = Runnable {
        if (touchIdx >= 0 && !moved) {
            longFired = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke(touchIdx)
        }
    }
    /** Page de 32 pas affichée (0 = pas 1–32, 1 = pas 33–64, …). */
    var viewPage = 0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arc = RectF()
    /** Nombre de notes longues qui passent sur chaque pas (0 = aucune). */
    private val covered = IntArray(MAX_STEPS)

    private var touchIdx = -1
    private var startY = 0f
    private var startVal = 0
    private var moved = false

    private fun cellW() = width / 8f
    private fun cellH() = height / 4f

    private fun indexAt(x: Float, y: Float): Int {
        val col = (x / cellW()).toInt().coerceIn(0, 7)
        val row = (y / cellH()).toInt().coerceIn(0, 3)
        return row * 8 + col
    }

    private fun get(s: Step): Int = when (param) {
        StepParam.NOTE -> s.note
        StepParam.VEL -> s.vel
        StepParam.DUR -> s.dur
        StepParam.CHORD -> s.chord
        StepParam.COND -> s.cond
        StepParam.RATCH -> s.ratch
    }

    private fun set(s: Step, v: Int) {
        when (param) {
            StepParam.NOTE -> s.note = v.coerceIn(0, 127)
            StepParam.VEL -> s.vel = v.coerceIn(1, 127)
            StepParam.DUR -> s.dur = v.coerceIn(0, Durs.LAST)
            StepParam.CHORD -> s.chord = v.coerceIn(0, Chords.LAST)
            StepParam.COND -> s.cond = v.coerceIn(0, Conds.LAST)
            StepParam.RATCH -> s.ratch = v.coerceIn(1, 8)
        }
    }

    private fun norm(v: Int): Float = when (param) {
        StepParam.NOTE, StepParam.VEL -> v / 127f
        StepParam.DUR -> v / Durs.LAST.toFloat()
        StepParam.CHORD -> v / Chords.LAST.toFloat()
        StepParam.COND -> v / Conds.LAST.toFloat()
        StepParam.RATCH -> (v - 1) / 7f
    }

    /** pixels de glissement par unité */
    private fun pxPerUnit() = when (param) {
        StepParam.NOTE -> dp(9)
        StepParam.VEL -> dp(2)
        StepParam.DUR, StepParam.CHORD, StepParam.COND, StepParam.RATCH -> dp(14)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tr = track ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchIdx = viewPage * PAGE_STEPS + indexAt(event.x, event.y)
                startY = event.y
                startVal = get(tr.steps[touchIdx])
                moved = false
                longFired = false
                removeCallbacks(longPress)
                postDelayed(longPress, 550)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchIdx < 0) return true
                val dy = startY - event.y
                if (longFired) return true
                if (!moved && abs(dy) > dp(8)) {
                    moved = true
                    removeCallbacks(longPress)
                    startY = event.y
                }
                if (moved) {
                    val s = tr.steps[touchIdx]
                    val delta = ((startY - event.y) / pxPerUnit()).toInt()
                    set(s, startVal + delta)
                    s.on = true
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (touchIdx >= 0 && !longFired) {
                    val s = tr.steps[touchIdx]
                    if (!moved) s.on = !s.on
                    onEdited?.invoke(touchIdx)
                }
                touchIdx = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touchIdx = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Repère les pas couverts par une note longue (dans la boucle). La ligne commence sur la note
     * et continue même par-dessus les notes suivantes ; plusieurs notes longues = ligne plus épaisse.
     * Une note tenue (∞) s'arrête à la note suivante de la piste, comme dans le moteur.
     */
    private fun computeCovered(tr: SeqTrack) {
        covered.fill(0)
        val len = tr.length
        for (i in 0 until len) {
            val s = tr.steps[i]
            if (!s.on) continue
            val extra = Durs.extraSteps(s.dur)
            if (extra <= 0) continue
            val inf = Durs.isInf(s.dur)
            covered[i]++
            var k = 1
            while (k <= extra && k < len) {
                val j = (i + k) % len
                if (inf && tr.steps[j].on) break
                covered[j]++
                k++
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val tr = track ?: return
        val cw = cellW()
        val ch = cellH()
        if (cw <= 0f || ch <= 0f) return
        val playing = tr.pos
        computeCovered(tr)
        if (Tdr.field) { drawField(canvas, tr, cw, ch, playing); return }

        for (cell in 0 until PAGE_STEPS) {
            val i = viewPage * PAGE_STEPS + cell
            val col = cell % 8
            val row = cell / 8
            val l = col * cw + dp(3)
            val t = row * ch + dp(3)
            val r = (col + 1) * cw - dp(3)
            val b = (row + 1) * ch - dp(3)
            val s = tr.steps[i]
            val inLoop = i < tr.length
            val alpha = if (inLoop) 255 else 60

            Tdr.cutPath(path, l, t, r, b, dp(7))
            p.style = Paint.Style.FILL
            p.color = if ((col / 4) % 2 == 0) Tdr.PANEL else Tdr.PANEL2
            p.alpha = alpha
            canvas.drawPath(path, p)

            // liaison : ce pas est couvert par une note longue
            if (covered[i] > 0 && inLoop) {
                p.color = accent
                p.alpha = 150
                canvas.drawRect(l, b - dp(4) - dp(3) * minOf(covered[i], 3), r, b - dp(4), p)
                p.alpha = 255
            }

            if (i == playing || i == touchIdx) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2.5f)
                p.color = if (i == touchIdx) Tdr.WHITE else Tdr.MAGENTA
                canvas.drawPath(path, p)
            }

            val cx = (l + r) / 2
            val cy = (t + b) / 2 - dp(2)
            val rad = min(r - l, b - t) / 2 - dp(6)
            if (rad < dp(6)) continue
            val ring = max(dp(2.5f), rad * 0.16f)
            arc.set(cx - rad, cy - rad, cx + rad, cy + rad)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ring
            p.color = Tdr.GRID
            p.alpha = alpha
            canvas.drawArc(arc, 135f, 270f, false, p)
            val v = get(s)
            p.color = if (s.on) accent else Tdr.DIM
            p.alpha = alpha
            canvas.drawArc(arc, 135f, 270f * norm(v), false, p)

            p.style = Paint.Style.FILL
            p.color = if (s.on) accent else Tdr.BG
            p.alpha = if (s.on) (if (inLoop) 255 else 70) else alpha
            canvas.drawCircle(cx, cy, rad - ring * 1.8f, p)

            p.typeface = Tdr.font
            p.textAlign = Paint.Align.CENTER
            p.textSize = max(dp(8), rad * 0.52f)
            val txt = if (!s.on) "OFF" else when (param) {
                StepParam.NOTE -> Tdr.noteName((s.note + tr.transpose).coerceIn(0, 127))
                StepParam.VEL -> v.toString()
                StepParam.DUR -> Durs.label(v)
                StepParam.CHORD -> Chords.names[v]
                StepParam.COND -> Conds.labels[v]
                StepParam.RATCH -> "×$v"
            }
            while (p.measureText(txt) > (rad - ring * 1.8f) * 1.8f && p.textSize > dp(6)) p.textSize -= dp(0.5f)
            p.color = if (s.on) Tdr.BG else Tdr.DIM
            p.alpha = alpha
            canvas.drawText(txt, cx, cy + p.textSize * 0.36f, p)

            // numéro du pas + petits repères (accord / durée)
            p.typeface = Tdr.mono
            p.textAlign = Paint.Align.LEFT
            p.textSize = dp(8)
            p.color = if (i == playing) Tdr.MAGENTA else Tdr.DIM
            p.alpha = alpha
            canvas.drawText("${i + 1}", l + dp(8), t + dp(10), p)
            if (s.on) {
                p.textAlign = Paint.Align.RIGHT
                p.color = accent
                val tags = buildString {
                    if (s.chord != 0 && param != StepParam.CHORD) append(Chords.names[s.chord])
                    if (Durs.isInf(s.dur) && param != StepParam.DUR) { if (isNotEmpty()) append(" "); append("∞") }
                    if (s.cond != 0 && param != StepParam.COND) { if (isNotEmpty()) append(" "); append(Conds.labels[s.cond]) }
                    if (s.ratch > 1 && param != StepParam.RATCH) { if (isNotEmpty()) append(" "); append("×${s.ratch}") }
                }
                if (tags.isNotEmpty()) canvas.drawText(tags, r - dp(4), t + dp(10), p)
            }
            p.alpha = 255
        }
    }

    private fun cellText(tr: SeqTrack, s: Step): String {
        val v = get(s)
        return when (param) {
            StepParam.NOTE -> Tdr.noteName((s.note + tr.transpose).coerceIn(0, 127))
            StepParam.VEL -> v.toString()
            StepParam.DUR -> Durs.label(v)
            StepParam.CHORD -> Chords.names[v]
            StepParam.COND -> Conds.labels[v]
            StepParam.RATCH -> "×$v"
        }
    }

    /**
     * FIELD : cases cerclées d'encre ; pas actif = encre pleine, texte papier ;
     * tête de lecture = accent ; notes longues = trait d'accent ; hors boucle = estompé.
     */
    private fun drawField(canvas: Canvas, tr: SeqTrack, cw: Float, ch: Float, playing: Int) {
        for (cell in 0 until PAGE_STEPS) {
            val i = viewPage * PAGE_STEPS + cell
            val col = cell % 8
            val row = cell / 8
            val l = col * cw + dp(2.5f)
            val t = row * ch + dp(2.5f)
            val r = (col + 1) * cw - dp(2.5f)
            val b = (row + 1) * ch - dp(2.5f)
            val s = tr.steps[i]
            val inLoop = i < tr.length
            val play = i == playing
            val alpha = if (inLoop) 255 else 70

            Tdr.cutPath(path, l, t, r, b, dp(6))
            p.style = Paint.Style.FILL
            p.color = when {
                play -> Tdr.ACCENT
                s.on -> Tdr.WHITE
                else -> Tdr.BG
            }
            p.alpha = alpha
            canvas.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(if (i == touchIdx) 3f else 1.5f)
            p.color = if (play) Tdr.ACCENT else Tdr.WHITE
            p.alpha = alpha
            canvas.drawPath(path, p)

            // valeur éditée : petite jauge en bas (VEL, DURÉE…), sauf en mode NOTE
            if (s.on && param != StepParam.NOTE) {
                p.style = Paint.Style.FILL
                p.color = if (play) Tdr.WHITE else Tdr.BG
                p.alpha = if (inLoop) 140 else 50
                canvas.drawRect(l + dp(6), b - dp(5), l + dp(6) + (r - l - dp(12)) * norm(get(s)), b - dp(3.5f), p)
            }
            // liaison (note longue) : trait d'accent
            if (covered[i] > 0 && inLoop) {
                p.style = Paint.Style.FILL
                p.color = Tdr.ACCENT
                p.alpha = 255
                // trait à l'intérieur de chaque case ; plus épais si plusieurs notes longues se superposent
                val th = dp(3) * minOf(covered[i], 4)
                canvas.drawRect(l + dp(6), b - dp(5) - th, r - dp(6), b - dp(5), p)
            }
            // repère « expression » (mode avancé) : petit carré d'accent en bas à droite
            if (s.on && s.hasExpression()) {
                p.style = Paint.Style.FILL
                p.color = Tdr.ACCENT
                p.alpha = alpha
                canvas.drawRect(r - dp(9), t + dp(13), r - dp(4), t + dp(18), p)
            }

            p.style = Paint.Style.FILL
            p.typeface = Tdr.mono
            p.textAlign = Paint.Align.LEFT
            p.textSize = dp(7.5f)
            p.color = when { play -> Tdr.WHITE; s.on -> Tdr.BG; else -> Tdr.DIM }
            p.alpha = alpha
            canvas.drawText("${i + 1}", l + dp(7), t + dp(10), p)
            if (s.on) {
                val tags = buildString {
                    if (s.chord != 0 && param != StepParam.CHORD) append(Chords.names[s.chord])
                    if (Durs.isInf(s.dur) && param != StepParam.DUR) { if (isNotEmpty()) append(" "); append("∞") }
                    if (s.cond != 0 && param != StepParam.COND) { if (isNotEmpty()) append(" "); append(Conds.labels[s.cond]) }
                    if (s.ratch > 1 && param != StepParam.RATCH) { if (isNotEmpty()) append(" "); append("×${s.ratch}") }
                }
                if (tags.isNotEmpty()) {
                    p.textAlign = Paint.Align.RIGHT
                    canvas.drawText(tags, r - dp(4), t + dp(10), p)
                }
            }

            p.typeface = Tdr.font
            p.textAlign = Paint.Align.CENTER
            val txt = if (s.on) cellText(tr, s) else "·"
            p.textSize = minOf(ch * 0.26f, dp(14))
            while (p.measureText(txt) > (r - l) - dp(8) && p.textSize > dp(6)) p.textSize -= dp(0.5f)
            p.color = when { play -> Tdr.WHITE; s.on -> Tdr.BG; else -> Tdr.DIM }
            p.alpha = alpha
            canvas.drawText(txt, (l + r) / 2, (t + b) / 2 - (p.descent() + p.ascent()) / 2 + dp(2), p)
            p.alpha = 255
        }
    }
}
