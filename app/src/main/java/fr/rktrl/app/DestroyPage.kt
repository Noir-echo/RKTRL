package fr.rktrl.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Surface X/Y façon Kaoss Pad, avec (en option) une animation géométrique par effet,
 * d'une seule couleur ; en MULTI, les animations des effets choisis se transforment l'une en l'autre.
 */
class XYPadView(ctx: Context, private val fx: Destroyer, private val bpm: () -> Int) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val poly = Path()
    private val trail = ArrayDeque<Pair<Float, Float>>()
    var onChange: (() -> Unit)? = null

    // centre de l'animation : suit le doigt en douceur, revient au milieu au relâchement
    private var ax = 0.5f
    private var ay = 0.5f
    private var energy = 0f          // 0 = au repos, 1 = effet en cours
    private val t0 = System.nanoTime()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val nx = (e.x / width).coerceIn(0f, 1f)
        val ny = (1f - e.y / height).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                trail.clear()
                fx.touchDown(nx, ny)
            }
            MotionEvent.ACTION_MOVE -> fx.touchMove(nx, ny)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fx.touchUp()
                trail.clear()
            }
        }
        if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
            trail.addLast(nx to ny)
            while (trail.size > 40) trail.removeFirst()
        }
        invalidate()
        onChange?.invoke()
        return true
    }


    /** Hachage déterministe (pour des motifs « aléatoires » stables d'une image à l'autre). */
    private fun hash(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    private var reelL = 0f
    private var reelR = 0f
    private var lastT = 0f

    private fun fr(v: Float) = v - kotlin.math.floor(v)
    private fun smooth(v: Float): Float { val c = v.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    private fun polygon(cx: Float, cy: Float, r: Float, sides: Int, rot: Float) {
        poly.reset()
        for (v in 0..sides) {
            val a = rot + v * 2f * Math.PI.toFloat() / sides
            val px = cx + kotlin.math.cos(a) * r
            val py = cy + kotlin.math.sin(a) * r
            if (v == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
        }
    }

    /**
     * Une animation par effet — une seule couleur (la couleur principale du thème), seule l'opacité varie.
     * a = opacité max (0–255), E = intensité (0 au repos, 1 pendant l'effet).
     */
    private fun drawEffect(e: Int, c: Canvas, cx: Float, cy: Float, w: Float, h: Float, maxR: Float,
                           t: Float, beats: Float, E: Float, a: Float) {
        val x = ax; val y = ay
        p.style = Paint.Style.STROKE
        p.color = Tdr.YELLOW
        fun alpha(f: Float) { p.alpha = (a * f).toInt().coerceIn(0, 255) }
        when (e) {
            Destroyer.REPEAT -> {
                // carrés emboîtés qui naissent au rythme du repeat
                val div = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 16.0)[Destroyer.pick(x, 8)].toFloat()
                val ph = fr(beats * (if (E > 0.5f) div else 0.5f))
                for (k in 0 until 12) {
                    val f = (k + ph) / 12f
                    val r = f * maxR * 1.2f
                    p.strokeWidth = dp(1 + 2.5f * (1 - f) * E)
                    alpha(1 - f)
                    polygon(cx, cy, r, 4, Math.PI.toFloat() / 4 + f * (0.5f + y))
                    c.drawPath(poly, p)
                }
            }
            Destroyer.STUTTER -> {
                // bandes horizontales hachées au rythme du découpage
                val bands = 6 + (x * 26).toInt()
                val chopIdx = kotlin.math.floor(beats * (1 + x * 7) * (0.3f + E)).toInt()
                val bh = h / bands
                p.style = Paint.Style.FILL
                for (k in 0 until bands) {
                    val open = hash(chopIdx, k) > y * 0.8f
                    if (!open) continue
                    alpha(0.15f + 0.35f * hash(k, chopIdx + 7))
                    c.drawRect(0f, k * bh + 1, w, (k + 1) * bh - dp(2), p)
                }
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1)
                alpha(0.8f)
                val scan = fr(beats * 0.5f) * h
                c.drawLine(0f, scan, w, scan, p)
            }
            Destroyer.ECHO -> {
                // un hexagone répété en spirale, chaque écho plus petit et plus pâle
                val copies = 3 + (y * 9).toInt()
                val spread = 0.15f + x * 0.5f
                for (k in 0 until copies) {
                    val f = k / copies.toFloat()
                    val ang = t * 0.6f + k * spread * 2.2f
                    val d = k * maxR * 0.06f * (1 + x)
                    val r = maxR * 0.32f * (1 - f * 0.75f)
                    p.strokeWidth = dp(1 + 2 * E * (1 - f))
                    alpha(1 - f)
                    polygon(cx + kotlin.math.cos(ang) * d, cy + kotlin.math.sin(ang) * d, r, 6, ang)
                    c.drawPath(poly, p)
                }
            }
            Destroyer.FILTER -> {
                // courbe de Lissajous fixe au centre, qui s'étire vers le doigt
                val mx = w / 2; val my = h / 2
                val dx = (x - 0.5f) * 2; val dy = (0.5f - y) * 2           // direction de l'appui (écran)
                val dl = kotlin.math.hypot(dx, dy).coerceAtLeast(0.0001f)
                val ux = dx / dl; val uy = dy / dl
                val amp = minOf(w, h) * 0.36f
                val pull = dl * amp * 1.3f * (0.25f + 0.75f * E)
                for (layer in 0 until 3) {
                    poly.reset()
                    val ph = t * 0.6f + layer * 0.3f
                    for (i in 0..240) {
                        val u = i / 240f * 2 * Math.PI.toFloat()
                        val bx = kotlin.math.sin(2 * u + ph) * amp * 1.3f
                        val by = kotlin.math.sin(3 * u) * amp
                        // les points du côté de l'appui sont tirés plus loin : déformation directionnelle
                        val proj = ((bx * ux + by * uy) / (amp * 1.3f)).coerceIn(-1f, 1f)
                        val k = ((proj + 1) / 2).let { it * it }
                        val px = mx + bx + ux * pull * k
                        val py = my + by + uy * pull * k
                        if (i == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
                    }
                    p.strokeWidth = dp(if (layer == 0) 1.5f + 1.5f * E else 1f)
                    alpha(if (layer == 0) 1f else 0.35f)
                    c.drawPath(poly, p)
                }
            }
            Destroyer.PITCH -> {
                // lignes verticales qui se courbent (bend) et ondulent (vibrato)
                val lines = 18
                val bend = (x - 0.5f) * w * 0.35f * (0.3f + E)
                val vib = kotlin.math.sin(t * 6 * 2 * Math.PI.toFloat()) * y * dp(14) * (0.3f + E)
                for (k in 0..lines) {
                    val bx = w * k / lines
                    poly.reset()
                    for (i in 0..24) {
                        val yy = h * i / 24f
                        val u = yy / h
                        val off = bend * kotlin.math.sin(u * Math.PI.toFloat()) + vib * kotlin.math.sin(u * 8 + k)
                        if (i == 0) poly.moveTo(bx + off, yy) else poly.lineTo(bx + off, yy)
                    }
                    val dist = kotlin.math.abs(bx - cx) / w
                    p.strokeWidth = dp(1 + 1.5f * E * (1 - dist))
                    alpha(0.25f + 0.75f * (1 - dist))
                    c.drawPath(poly, p)
                }
            }
            Destroyer.TAPE -> {
                // deux bobines fixes au centre ; celle du côté où l'on appuie freine jusqu'à l'arrêt
                val mx = w / 2; val my = h / 2
                val rr = minOf(w, h) * 0.3f
                val stopL = E * smooth((0.5f - x) * 2.5f)
                val stopR = E * smooth((x - 0.5f) * 2.5f)
                for (side in intArrayOf(-1, 1)) {
                    val bx = mx + side * rr * 1.25f
                    val stop = if (side < 0) stopL else stopR
                    val ang0 = if (side < 0) reelL else reelR
                    for (ring in 1..4) {
                        p.strokeWidth = dp(1 + E)
                        alpha((0.3f + 0.15f * ring) * (1 - 0.4f * stop))
                        c.drawCircle(bx, my, rr * ring / 4f, p)
                    }
                    for (k in 0 until 6) {
                        val ang = ang0 + k * Math.PI.toFloat() / 3
                        alpha(0.9f - 0.4f * stop)
                        p.strokeWidth = dp(2)
                        c.drawLine(bx, my, bx + kotlin.math.cos(ang) * rr, my + kotlin.math.sin(ang) * rr, p)
                    }
                }
                alpha(0.5f)
                p.strokeWidth = dp(1)
                c.drawLine(mx - rr * 1.25f, my + rr, mx + rr * 1.25f, my + rr, p)
            }
            Destroyer.SCATTER -> {
                // réseau de triangles qui se dispersent
                val cols = 12; val rows = 7
                val step = kotlin.math.floor(beats * (1 + x * 3)).toInt()
                val amp = y * dp(40) * (0.2f + E)
                for (i in 0 until cols) for (j in 0 until rows) {
                    val bx = (i + 0.5f) * w / cols
                    val by = (j + 0.5f) * h / rows
                    val jx = (hash(step, i * 31 + j) - 0.5f) * 2 * amp
                    val jy = (hash(step + 3, i * 17 + j) - 0.5f) * 2 * amp
                    val d = kotlin.math.hypot(bx - cx, by - cy) / maxR
                    alpha(0.25f + 0.75f * (1 - d).coerceAtLeast(0f))
                    p.strokeWidth = dp(1.2f)
                    polygon(bx + jx, by + jy, dp(6 + 4 * E), 3, t * 0.5f + hash(i, j) * 6)
                    c.drawPath(poly, p)
                }
            }
            Destroyer.OCTAVE -> {
                // triangles imbriqués à l'échelle 2^k (octaves), qui pulsent au temps
                val pulse = 1f + 0.08f * (1 - fr(beats)) * E
                val base = minOf(w, h) * 0.46f * pulse
                for (k in 0 until 6) {
                    val r = base / Math.pow(2.0, k * (0.5 + x * 0.5)).toFloat()
                    val rot = t * 0.3f * (if (k % 2 == 0) 1 else -1) + y * Math.PI.toFloat()
                    p.strokeWidth = dp(1 + 2 * E * (1 - k / 6f))
                    alpha(1 - k / 7f)
                    polygon(cx, cy, r, 3, rot - Math.PI.toFloat() / 2)
                    c.drawPath(poly, p)
                    polygon(cx, cy, r, 3, rot + Math.PI.toFloat() / 2)
                    alpha(0.4f * (1 - k / 7f))
                    c.drawPath(poly, p)
                }
            }
            else -> {
                // WARP : tunnel de polygones, X = vitesse, Y haut = sens inversé
                val dir = if (y > 0.5f && E > 0.5f) -1f else 1f
                val flow = fr(t * (0.2f + E * (0.3f + x * 2f)) * dir)
                val sides = 5 + (x * 3).toInt()
                for (k in 0 until 16) {
                    val f = (k + flow) / 16f
                    val r = f * f * maxR * 1.1f
                    if (r < dp(3)) continue
                    p.strokeWidth = dp(1 + 2 * E * (1 - f))
                    alpha(1 - f)
                    polygon(cx, cy, r, sides, k * 0.18f + t * 0.2f * dir)
                    c.drawPath(poly, p)
                }
            }
        }
        p.alpha = 255
    }

    private fun drawVisuals(canvas: Canvas, w: Float, h: Float, engaged: Boolean) {
        val t = (System.nanoTime() - t0) / 1e9f
        // angle des bobines (TAPE STOP) intégré image par image : pas de saut quand la vitesse change
        val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
        val base = 1.4f
        reelL += dt * base * (1 - energy * smooth((0.5f - ax) * 2.5f))
        reelR += dt * base * (1 - energy * smooth((ax - 0.5f) * 2.5f))
        // lissage du centre et de l'intensité
        val tx = if (engaged) fx.x else 0.5f
        val ty = if (engaged) fx.y else 0.5f
        ax += (tx - ax) * 0.18f
        ay += (ty - ay) * 0.18f
        energy += ((if (engaged) 1f else 0f) - energy) * 0.08f

        val cx = ax * w
        val cy = (1 - ay) * h
        val maxR = kotlin.math.hypot(maxOf(cx, w - cx), maxOf(cy, h - cy))
        val beats = t * bpm() / 60f
        val a = 70f + 170f * energy

        val list = (0 until Destroyer.COUNT).filter { fx.armed[it] }
        if (list.isEmpty()) return
        canvas.save()
        canvas.clipPath(path)
        if (list.size == 1) {
            drawEffect(list[0], canvas, cx, cy, w, h, maxR, t, beats, energy, a)
        } else {
            // MULTI : les animations se transforment l'une en l'autre (zoom + rotation + fondu), toutes les 4 mesures… de temps
            val seg = beats / 4f
            val i = kotlin.math.floor(seg).toInt()
            val cur = list[Math.floorMod(i, list.size)]
            val nxt = list[Math.floorMod(i + 1, list.size)]
            val m = smooth((fr(seg) - 0.55f) / 0.45f)
            canvas.save()
            canvas.rotate(m * 45f, cx, cy)
            canvas.scale(1 + m * 0.7f, 1 + m * 0.7f, cx, cy)
            drawEffect(cur, canvas, cx, cy, w, h, maxR, t, beats, energy, a * (1 - m))
            canvas.restore()
            if (m > 0f) {
                canvas.save()
                canvas.rotate(-(1 - m) * 45f, cx, cy)
                canvas.scale(0.3f + 0.7f * m, 0.3f + 0.7f * m, cx, cy)
                drawEffect(nxt, canvas, cx, cy, w, h, maxR, t, beats, energy, a * m)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val engaged = fx.isEngaged()

        Tdr.cutPath(path, dp(1), dp(1), w - dp(1), h - dp(1), dp(16))
        p.style = Paint.Style.FILL
        p.color = Tdr.PANEL
        canvas.drawPath(path, p)

        val anim = Settings.fxVisuals
        if (anim) drawVisuals(canvas, w, h, engaged)

        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(Tdr.strokeDp)
        p.color = if (engaged) Tdr.ACCENT else if (Tdr.field) Tdr.WHITE else Tdr.GRID
        canvas.drawPath(path, p)

        if (!anim) {
            // grille simple
            p.strokeWidth = dp(1)
            p.color = Tdr.GRID
            for (i in 1 until 8) {
                canvas.drawLine(w * i / 8f, dp(6), w * i / 8f, h - dp(6), p)
                canvas.drawLine(dp(6), h * i / 8f, w - dp(6), h * i / 8f, p)
            }
            p.color = Tdr.DIM
            canvas.drawLine(w / 2, dp(6), w / 2, h - dp(6), p)
            canvas.drawLine(dp(6), h / 2, w - dp(6), h / 2, p)
        }

        // traînée
        p.style = Paint.Style.FILL
        trail.forEachIndexed { i, (tx, ty) ->
            p.color = Tdr.ACCENT
            p.alpha = (40 + 200 * i / trail.size.coerceAtLeast(1))
            canvas.drawCircle(tx * w, (1 - ty) * h, dp(3), p)
        }
        p.alpha = 255

        // curseur (seulement pendant l'appui)
        if (engaged) {
            val cx = fx.x * w
            val cy = (1 - fx.y) * h
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1.5f)
            p.color = Tdr.ACCENT
            canvas.drawLine(cx, dp(4), cx, h - dp(4), p)
            canvas.drawLine(dp(4), cy, w - dp(4), cy, p)
            // seul le petit cercle central reste
            p.style = Paint.Style.FILL
            p.color = Tdr.WHITE
            canvas.drawCircle(cx, cy, dp(4), p)
        }

        // légende des effets armés
        p.typeface = Tdr.mono
        p.textAlign = Paint.Align.LEFT
        p.textSize = dp(10)
        var ty = dp(18)
        for (e in 0 until Destroyer.COUNT) {
            if (!fx.armed[e]) continue
            p.color = Tdr.WHITE
            canvas.drawText(Destroyer.names[e] + "  " + fx.describe(e), dp(12), ty, p)
            ty += dp(13)
        }
        p.textAlign = Paint.Align.RIGHT
        p.color = Tdr.DIM
        canvas.drawText("X →", w - dp(10), h - dp(10), p)
        p.textAlign = Paint.Align.LEFT
        canvas.drawText("↑ Y", dp(10), h - dp(10), p)

        // animation continue tant que la page est visible
        if (anim && isShown) postInvalidateOnAnimation()
    }
}

/**
 * Page 3 — DESTROY MODE : pad X/Y d'effets MIDI live (tout revient à l'origine au relâchement),
 * canaux ciblés, effets, TAP BPM, pads assignables.
 */
class DestroyPage(private val act: Activity, private val fx: Destroyer, private val seq: Sequencer) {

    var onNormal: (() -> Unit)? = null
    var onLogo: (() -> Unit)? = null
    var onLink: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null

    lateinit var header: HeaderBar
        private set
    private lateinit var chans: TrackTabsView
    private lateinit var pad: XYPadView
    private val fxBtns = ArrayList<TdrButton>()
    private lateinit var btnMulti: TdrButton
    private var lastPicked = 0
    private val padBtns = ArrayList<TdrButton>()
    private lateinit var btnTap: TdrButton
    private val taps = ArrayList<Long>()
    var onTempo: (() -> Unit)? = null
    private lateinit var btnEditPads: TdrButton
    private lateinit var btnPlay: TdrButton
    private var editPads = false

    val view: View = build()

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val c: Context = act
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Tdr.BG)

        header = HeaderBar(c, "03", "DESTROY MODE")
        header.onLogo = { onLogo?.invoke() }
        header.onLink = { onLink?.invoke() }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(34)))

        val mid = LinearLayout(c)
        mid.orientation = LinearLayout.HORIZONTAL
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- gauche : canaux MIDI ciblés
        val left = LinearLayout(c)
        left.orientation = LinearLayout.VERTICAL
        val llp = LinearLayout.LayoutParams(c.dpi(150), ViewGroup.LayoutParams.MATCH_PARENT)
        llp.leftMargin = c.dpi(6); llp.topMargin = c.dpi(4); llp.bottomMargin = c.dpi(2)
        mid.addView(left, llp)
        chans = TrackTabsView(c,
            label = { Settings.channelNames[it].ifBlank { tr("CANAL", "CHANNEL") } },
            sub = { "CH %02d".format(it + 1) },
            isOn = { (fx.mask shr it) and 1 == 1 },
            isDim = { false },
            hasContent = { false })
        chans.onTap = { i -> fx.mask = fx.mask xor (1 shl i); onSave?.invoke() }
        left.addView(chans, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val allRow = LinearLayout(c)
        allRow.orientation = LinearLayout.HORIZONTAL
        val b1 = TdrButton(c, tr("TOUS", "ALL"), Tdr.CYAN).onClick { fx.mask = 0xFFFF; chans.invalidate(); onSave?.invoke() }
        val b2 = TdrButton(c, tr("AUCUN", "NONE"), Tdr.CYAN).onClick { fx.mask = 0; chans.invalidate(); onSave?.invoke() }
        val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); l1.rightMargin = c.dpi(4)
        allRow.addView(b1, l1)
        allRow.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(30)); alp.topMargin = c.dpi(4)
        left.addView(allRow, alp)

        // ---- centre : pad X/Y
        pad = XYPadView(c, fx) { seq.bpm }
        val plp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        plp.leftMargin = c.dpi(6); plp.rightMargin = c.dpi(4); plp.topMargin = c.dpi(4); plp.bottomMargin = c.dpi(2)
        mid.addView(pad, plp)

        // ---- droite : effets + TAP BPM
        val scroll = ScrollView(c)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(c.dpi(4), c.dpi(2), c.dpi(10), c.dpi(4))
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mid.addView(scroll, LinearLayout.LayoutParams(c.dpi(176), ViewGroup.LayoutParams.MATCH_PARENT))

        fun add(v: View, h: Int) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(h))
            lp.topMargin = c.dpi(3)
            col.addView(v, lp)
        }
        val sec = Tdr.label(c, tr("▌ EFFETS", "▌ EFFECTS"), 10f, Tdr.YELLOW)
        sec.setPadding(0, c.dpi(4), 0, c.dpi(1))
        col.addView(sec)
        for (e in 0 until Destroyer.COUNT) {
            val b = TdrButton(c, Destroyer.names[e], Tdr.trackColor(e))
            b.active = fx.armed[e]
            b.onClick { pickEffect(e) }
            fxBtns.add(b)
            add(b, 32)
        }
        val sec2 = Tdr.label(c, "▌ MODE", 10f, Tdr.YELLOW)
        sec2.setPadding(0, c.dpi(6), 0, c.dpi(1))
        col.addView(sec2)
        btnTap = TdrButton(c, "TAP BPM", Tdr.RED)
        if (Tdr.field) btnTap.hazard = true else btnTap.emergency = true
        btnTap.setOnTouchListener { v, e ->
            // réagit dès l'appui (plus précis qu'au relâchement)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; tap() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
        add(btnTap, 54)
        btnMulti = TdrButton(c, "MULTI", Tdr.CYAN).onClick {
            fx.multi = !fx.multi
            if (!fx.multi) {
                // retour à un seul effet : on garde le dernier choisi (ou le premier allumé)
                val keep = if (fx.armed[lastPicked]) lastPicked else fx.armed.indexOfFirst { it }.coerceAtLeast(0)
                for (i in 0 until Destroyer.COUNT) fx.setArmed(i, i == keep)
            }
            refreshFx()
            onSave?.invoke()
        }
        add(btnMulti, 40)
        val hint = Tdr.label(c, tr("TAP : tape au moins 3 fois en rythme, le tempo suit. MULTI : plusieurs effets à la fois (sinon, toucher un effet le remplace). Les effets s'arrêtent dès que tu lèves le doigt du pad.",
            "TAP: tap at least 3 times in time, the tempo follows. MULTI: several effects at once (otherwise tapping an effect replaces it). Effects stop as soon as you lift your finger from the pad."), 9f, Tdr.DIM)
        hint.setPadding(0, c.dpi(4), 0, 0)
        col.addView(hint)
        refreshTap()

        // ---- barre du bas : 8 pads + réglages + retour
        val bar = LinearLayout(c)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(c.dpi(6), c.dpi(4), c.dpi(8), c.dpi(6))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dpi(56)))
        for (i in 0 until 8) {
            val b = TdrButton(c, fx.pads[i].name, Tdr.trackColor(i), "CH${fx.pads[i].channel} ${Tdr.noteName(fx.pads[i].note)}")
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        if (!editPads) fx.padDown(i)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (editPads) { editPad(i); editPads = false; btnEditPads.active = false } else fx.padUp(i)
                    }
                    MotionEvent.ACTION_CANCEL -> { v.isPressed = false; if (!editPads) fx.padUp(i) }
                }
                true
            }
            padBtns.add(b)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.rightMargin = c.dpi(4)
            bar.addView(b, lp)
        }
        btnEditPads = TdrButton(c, tr("EDIT PADS", "EDIT PADS"), Tdr.WHITE).onClick {
            editPads = !editPads; btnEditPads.active = editPads
        }
        btnPlay = TdrButton(c, "▶ PLAY", Tdr.GREEN).onClick { seq.toggle(); refreshTransport() }
        val btnNormal = TdrButton(c, "NORMAL MODE", Tdr.YELLOW)
        btnNormal.hazard = true
        btnNormal.onClick { onNormal?.invoke() }
        val e1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f); e1.leftMargin = c.dpi(8); e1.rightMargin = c.dpi(4)
        bar.addView(btnEditPads, e1)
        val e2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f); e2.rightMargin = c.dpi(4)
        bar.addView(btnPlay, e2)
        bar.addView(btnNormal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f))

        refreshTransport()
        return root
    }

    /** Choix d'un effet : MULTI = on l'ajoute / le retire ; sinon il remplace l'effet en cours. */
    private fun pickEffect(e: Int) {
        lastPicked = e
        if (fx.multi) {
            val on = !fx.armed[e]
            if (!on && fx.armed.count { it } <= 1) return       // toujours au moins un effet
            fx.setArmed(e, on)
        } else {
            for (i in 0 until Destroyer.COUNT) if (i != e && fx.armed[i]) fx.setArmed(i, false)
            fx.setArmed(e, true)
        }
        refreshFx()
        onSave?.invoke()
    }

    private fun refreshFx() {
        fxBtns.forEachIndexed { i, b -> b.active = fx.armed[i] }
        btnMulti.active = fx.multi
        pad.invalidate()
    }

    /** TAP BPM : moyenne des derniers intervalles ; on repart de zéro après 2 s sans tape. */
    private fun tap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        while (taps.size > 6) taps.removeAt(0)
        if (taps.size >= 3) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            seq.bpm = (60000.0 / avg).toInt().coerceIn(30, 300)
            onTempo?.invoke()
        }
        refreshTap()
    }

    private fun refreshTap() {
        btnTap.text = if (taps.size in 1..2) "TAP ${"•".repeat(taps.size)}" else "TAP · ${seq.bpm} BPM"
    }

    fun refreshTransport() {
        if (seq.running) { btnPlay.text = "■ STOP"; btnPlay.accent = Tdr.RED; btnPlay.active = !Tdr.field }
        else { btnPlay.text = "▶ PLAY"; btnPlay.accent = Tdr.GREEN; btnPlay.active = false }
        header.running = seq.running
    }

    fun onShow() {
        chans.invalidate(); pad.invalidate()
        refreshTap()
        refreshFx()
    }

    private fun editPad(i: Int) {
        val c: Context = act
        val pc = fx.pads[i]
        val d = TdrDialog(c, "PAD ${i + 1}", Tdr.trackColor(i))
        d.section(tr("NOM", "NAME"))
        val name = tdrEdit(c, pc.name, 14f, 10)
        d.row(name, 42)
        d.section(tr("NOTE JOUÉE", "NOTE PLAYED"))
        val chHint = Tdr.label(c, Settings.channelLabel(pc.channel), 10f, Tdr.CYAN)
        val ch = Stepper(c, tr("CANAL MIDI", "MIDI CHANNEL"), 1, 16, pc.channel, editable = true,
            onChange = { chHint.text = Settings.channelLabel(it) })
        d.row(ch)
        d.body.addView(chHint)
        val note = Stepper(c, "NOTE", 0, 127, pc.note, fmt = { "${Tdr.noteName(it)}  ($it)" })
        d.row(note)
        val vel = Stepper(c, tr("VÉLOCITÉ", "VELOCITY"), 1, 127, pc.vel, editable = true)
        d.row(vel)
        d.note(tr("Les effets REPEAT, STUTTER, ECHO et OCTAVER s'appliquent aussi aux pads.",
            "REPEAT, STUTTER, ECHO and OCTAVER also apply to the pads."))
        d.action(tr("ANNULER", "CANCEL"), Tdr.DIM) { d.dismiss() }
        d.action(tr("VALIDER", "OK"), primary = true) {
            pc.name = name.text.toString().ifBlank { "PAD ${i + 1}" }
            pc.channel = ch.value
            pc.note = note.value
            pc.vel = vel.value
            padBtns[i].text = pc.name
            padBtns[i].code = "CH${pc.channel} ${Tdr.noteName(pc.note)}"
            onSave?.invoke()
            d.dismiss()
        }
        d.show()
    }
}
