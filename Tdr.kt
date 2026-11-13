package fr.rktrl.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/** Langue de l'interface. AUREBESH = textes anglais affichés avec la police Aurebesh. */
object Lang {
    const val FR = 0
    const val EN = 1
    const val AUREBESH = 2
    var current = FR
}

/** Texte selon la langue : français, ou anglais (aussi utilisé en Aurebesh). */
fun tr(fr: String, en: String): String = if (Lang.current == Lang.FR) fr else en

/** Réglages globaux de l'appli (sauvegardés dans les préférences et le fichier de sauvegarde). */
object Settings {
    /** Noms des 16 canaux MIDI (vide = pas de nom). */
    val channelNames = Array(16) { "" }
    @Volatile var midiClock = true
    /** Animations géométriques dans le pad X/Y du Destroy Mode. */
    var fxVisuals = true
    var theme = Themes.FIELD
    /** Couleurs de l'interface FIELD (papier + accent, choisies par l'utilisateur). */
    var fieldPaper = 0xFFE7E6E1.toInt()
    var fieldAccent = 0xFFFF4F00.toInt()
    /** Couleurs de l'interface sobre (choisies par l'utilisateur). */
    var soberBg = 0xFF0A0A0C.toInt()
    var soberMain = 0xFFFFE100.toInt()
    var soberAccent = 0xFFFF2A2A.toInt()

    /** « 01 » ou « 01 BASSE » selon que le canal a un nom. */
    fun channelLabel(ch: Int): String {
        val n = channelNames.getOrNull(ch - 1) ?: ""
        return if (n.isBlank()) "%02d".format(ch) else "%02d %s".format(ch, n)
    }
}

/** Interfaces disponibles. */
object Themes {
    const val ORIGINAL = 0
    const val SOBER = 1       // variante sobre : 3 couleurs au choix
    const val FIELD = 2       // papier + encre + un accent (esprit appareils de studio)
    /** Ordre d'affichage dans les options. */
    val order = intArrayOf(FIELD, ORIGINAL, SOBER)
    fun name(t: Int) = when (t) {
        FIELD -> "FIELD"
        SOBER -> tr("SOBRE (COULEURS AU CHOIX)", "SOBER (CUSTOM COLOURS)")
        else -> tr("ORIGINALE", "ORIGINAL")
    }
}

/**
 * Palette et outils graphiques. Les couleurs dépendent du thème choisi (Tdr.apply).
 * Thème d'origine : esprit Wipeout / The Designers Republic.
 */
object Tdr {
    var BG = 0; var PANEL = 0; var PANEL2 = 0; var GRID = 0; var DIM = 0; var WHITE = 0
    var YELLOW = 0; var CYAN = 0; var MAGENTA = 0; var ORANGE = 0; var GREEN = 0; var VIOLET = 0; var RED = 0
    /** Coins arrondis (thème Groove) au lieu de coins coupés. */
    var round = false
    /** Couleur d'accent (sélection, tête de lecture, fader, rayures, ▶ / ■). */
    var ACCENT = 0
    /** Thème FIELD actif : dessin « papier et encre ». */
    var field = false
    /** Épaisseur des contours. */
    var strokeDp = 2f
    private var palette = IntArray(8)

    init { apply() }

    fun apply() {
        round = false
        field = Settings.theme == Themes.FIELD
        strokeDp = if (field) 1.5f else 2f
        if (field) {
            // papier, encre noire, un seul accent
            val p = Settings.fieldPaper; val ink = if (lum(p) > 0.45f) 0xFF111111.toInt() else 0xFFEDEDE8.toInt()
            BG = p; PANEL = p; PANEL2 = mix(p, ink, 0.07f); GRID = mix(p, ink, 0.25f); DIM = mix(p, ink, 0.55f)
            WHITE = ink
            YELLOW = ink; CYAN = ink; ORANGE = ink; GREEN = ink; VIOLET = ink; MAGENTA = ink
            ACCENT = Settings.fieldAccent; RED = ACCENT
            palette = intArrayOf(ink)
        } else if (Settings.theme == Themes.SOBER) {
            // même dessin que l'originale, mais seulement : fond + couleur principale + accent
            val b = Settings.soberBg; val m = Settings.soberMain; val a = Settings.soberAccent
            BG = b; PANEL = mix(b, m, 0.07f); PANEL2 = mix(b, m, 0.13f); GRID = mix(b, m, 0.24f); DIM = mix(b, m, 0.48f)
            WHITE = mix(m, if (lum(b) < 0.5f) Color.WHITE else Color.BLACK, 0.55f)
            YELLOW = m; CYAN = m; ORANGE = m; GREEN = m; VIOLET = m
            MAGENTA = a; RED = a; ACCENT = a
            palette = intArrayOf(m, a)
        } else {
            BG = 0xFF0A0A0C.toInt(); PANEL = 0xFF141418.toInt(); PANEL2 = 0xFF1D1D24.toInt()
            GRID = 0xFF2C2C36.toInt(); DIM = 0xFF60606C.toInt(); WHITE = 0xFFF0F0F0.toInt()
            YELLOW = 0xFFFFE100.toInt(); CYAN = 0xFF00D8FF.toInt(); MAGENTA = 0xFFFF2D87.toInt()
            ORANGE = 0xFFFF6A00.toInt(); GREEN = 0xFF7CFF4F.toInt(); VIOLET = 0xFFA66BFF.toInt(); RED = 0xFFFF2A2A.toInt()
            palette = intArrayOf(YELLOW, CYAN, MAGENTA, ORANGE, GREEN, VIOLET, 0xFF4FA8FF.toInt(), 0xFFFF9EC7.toInt())
            ACCENT = YELLOW
        }
    }

    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    /** Couleur d'une piste / d'un canal (16, 8 couleurs qui alternent). */
    fun trackColor(i: Int): Int = palette[Math.floorMod(i, palette.size)]

    private var condensed: Typeface = Typeface.DEFAULT_BOLD
    private var monoTf: Typeface = Typeface.MONOSPACE
    private var aurebesh: Typeface? = null
    private var spaceBold: Typeface? = null
    private var spaceRegular: Typeface? = null
    private var spaceBoldItalic: Typeface? = null

    fun init(ctx: Context) {
        condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        monoTf = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        fun load(f: String) = try { Typeface.createFromAsset(ctx.assets, "fonts/$f") } catch (e: Exception) { null }
        aurebesh = load("aurebesh.ttf")
        spaceBold = load("spacemono_bold.ttf")
        spaceRegular = load("spacemono_regular.ttf")
        spaceBoldItalic = load("spacemono_bolditalic.ttf")
    }

    private val themeFont: Typeface get() = if (this.field) spaceBold ?: monoTf else condensed
    private val themeMono: Typeface get() = if (this.field) spaceRegular ?: monoTf else monoTf
    /** Police du logo (italique en FIELD). */
    val logo: Typeface get() = if (Lang.current == Lang.AUREBESH) aurebesh ?: themeFont
                               else if (this.field) spaceBoldItalic ?: themeFont else themeFont

    /** Police courante (Aurebesh si cette langue est choisie). */
    val font: Typeface get() = if (Lang.current == Lang.AUREBESH) aurebesh ?: themeFont else themeFont
    val mono: Typeface get() = if (Lang.current == Lang.AUREBESH) aurebesh ?: themeMono else themeMono
    /** Police toujours lisible (choix de langue). */
    val latin: Typeface get() = themeFont

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val ROOTS: Array<String> get() = NOTE_NAMES.copyOf()

    /** Nom de note, convention C4 = 60. */
    fun noteName(n: Int): String = NOTE_NAMES[Math.floorMod(n, 12)] + (Math.floorDiv(n, 12) - 1)

    private val rr = RectF()

    /** Forme des boutons/panneaux : coins coupés (ou arrondis pour le thème Groove). */
    fun cutPath(path: Path, l: Float, t: Float, r: Float, b: Float, cut: Float) {
        path.reset()
        if (round) {
            rr.set(l, t, r, b)
            val rad = cut * 0.9f
            path.addRoundRect(rr, rad, rad, Path.Direction.CW)
            return
        }
        path.moveTo(l + cut, t)
        path.lineTo(r, t)
        path.lineTo(r, b - cut)
        path.lineTo(r - cut, b)
        path.lineTo(l, b)
        path.lineTo(l, t + cut)
        path.close()
    }

    fun label(ctx: Context, s: String, sp: Float = 12f, color: Int = WHITE, tf: Typeface = font): TextView {
        val tv = TextView(ctx)
        tv.text = s
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(color)
        tv.typeface = tf
        tv.includeFontPadding = false
        return tv
    }
}

fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Float = context.dp(v)
fun View.dpi(v: Number): Int = context.dpi(v)

/** Fond de panneau à coins coupés avec liseré coloré. */
class PanelDrawable(
    private val ctx: Context,
    private val accent: Int,
    private val fill: Int = Tdr.PANEL,
    private val cutDp: Float = 14f
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val sw = ctx.dp(Tdr.strokeDp)
        Tdr.cutPath(path, b.left + sw / 2, b.top + sw / 2, b.right - sw / 2, b.bottom - sw / 2, ctx.dp(cutDp))
        p.style = Paint.Style.FILL
        p.color = fill
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = accent
        canvas.drawPath(path, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
