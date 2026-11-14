package fr.rktrl.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Réglage d'un bouton rond. `value` = dernière valeur connue de l'instrument. */
class KnobConfig(
    var channel: Int,
    var cc: Int,
    var min: Int = 0,
    var max: Int = 127,
    var label: String = "",
    var value: Int = 0
)

object CcNames {
    private val names = mapOf(
        0 to "BANK SELECT", 1 to "MODULATION", 2 to "BREATH", 4 to "FOOT", 5 to "PORTAMENTO TIME",
        7 to "VOLUME", 8 to "BALANCE", 10 to "PAN", 11 to "EXPRESSION", 64 to "SUSTAIN",
        65 to "PORTAMENTO ON/OFF", 71 to "RESONANCE", 72 to "RELEASE", 73 to "ATTACK",
        74 to "CUTOFF", 75 to "DECAY", 76 to "VIBRATO RATE", 77 to "VIBRATO DEPTH",
        78 to "VIBRATO DELAY", 91 to "REVERB", 93 to "CHORUS", 94 to "DETUNE", 95 to "PHASER",
        120 to "ALL SOUND OFF", 121 to "RESET CONTROLLERS", 123 to "ALL NOTES OFF"
    )
    fun of(cc: Int): String = names[cc] ?: tr("CC LIBRE (selon l'instrument)", "FREE CC (instrument specific)")
}

object Store {
    private const val VERSION = 3
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("rktrl", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ réglages globaux
    fun saveLang(ctx: Context, l: Int) = prefs(ctx).edit().putInt("lang", l).apply()
    fun loadLang(ctx: Context): Int = prefs(ctx).getInt("lang", Lang.FR).coerceIn(0, 2)

    private fun settingsToJson(): JSONObject {
        val names = JSONArray()
        Settings.channelNames.forEach { names.put(it) }
        return JSONObject().put("chNames", names).put("clock", Settings.midiClock)
            .put("th2", Settings.theme).put("fPaper", Settings.fieldPaper).put("fAcc", Settings.fieldAccent).put("sBg", Settings.soberBg).put("sMain", Settings.soberMain)
            .put("sAcc", Settings.soberAccent).put("fxVis", Settings.fxVisuals)
    }

    private fun settingsFromJson(o: JSONObject) {
        o.optJSONArray("chNames")?.let { a -> for (i in 0 until 16) Settings.channelNames[i] = a.optString(i, "").take(16) }
        Settings.midiClock = o.optBoolean("clock", true)
        Settings.fxVisuals = o.optBoolean("fxVis", true)
        Settings.fieldPaper = o.optInt("fPaper", Settings.fieldPaper)
        Settings.fieldAccent = o.optInt("fAcc", Settings.fieldAccent)
        if (o.has("th2")) {
            Settings.theme = Themes.FIELD   // seule interface proposée désormais
            Settings.soberBg = o.optInt("sBg", Settings.soberBg)
            Settings.soberMain = o.optInt("sMain", Settings.soberMain)
            Settings.soberAccent = o.optInt("sAcc", Settings.soberAccent)
        } else if (o.has("th")) {
            // sauvegardes plus anciennes : on passe à la nouvelle interface FIELD
            Settings.theme = Themes.FIELD
            Settings.soberBg = o.optInt("sBg", Settings.soberBg)
            Settings.soberMain = o.optInt("sMain", Settings.soberMain)
            Settings.soberAccent = o.optInt("sAcc", Settings.soberAccent)
        } else {
            // v1.2 : 4 thèmes. L'ancien « neutre » (3) devient la variante sobre, les autres l'originale.
            val old = o.optInt("theme", 0)
            Settings.theme = Themes.FIELD
            if (old == 3) {
                Settings.soberBg = o.optInt("nBg", Settings.soberBg)
                Settings.soberMain = o.optInt("nFg", Settings.soberMain)
                Settings.soberAccent = Settings.soberMain
            }
        }
    }

    fun saveSettings(ctx: Context) = prefs(ctx).edit().putString("settings", settingsToJson().toString()).apply()
    fun loadSettings(ctx: Context) {
        val s = prefs(ctx).getString("settings", null) ?: return
        try { settingsFromJson(JSONObject(s)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ boutons
    const val KNOBS = 128

    /** Valeurs par défaut : 8 rangées de 16 (un par canal) — volume, cutoff, pan, réverb, résonance, attaque, release, chorus. */
    fun defaultKnob(i: Int): KnobConfig {
        val ch = i % 16 + 1
        return when (i / 16) {
            0 -> KnobConfig(ch, 7, label = "VOL $ch", value = 100)
            1 -> KnobConfig(ch, 74, label = "CUTOFF $ch", value = 64)
            2 -> KnobConfig(ch, 10, label = "PAN $ch", value = 64)
            3 -> KnobConfig(ch, 91, label = "REVERB $ch", value = 40)
            4 -> KnobConfig(ch, 71, label = "RESO $ch", value = 64)
            5 -> KnobConfig(ch, 73, label = "ATTACK $ch", value = 64)
            6 -> KnobConfig(ch, 72, label = "RELEASE $ch", value = 64)
            else -> KnobConfig(ch, 93, label = "CHORUS $ch", value = 0)
        }
    }

    fun defaultKnobs(): MutableList<KnobConfig> = MutableList(KNOBS) { defaultKnob(it) }

    private fun knobsToJson(knobs: List<KnobConfig>): JSONArray {
        val arr = JSONArray()
        knobs.forEach {
            arr.put(JSONObject()
                .put("ch", it.channel).put("cc", it.cc).put("min", it.min).put("max", it.max)
                .put("label", it.label).put("value", it.value))
        }
        return arr
    }

    private fun knobsFromJson(arr: JSONArray): MutableList<KnobConfig>? {
        if (arr.length() != 32 && arr.length() != KNOBS) return null
        return MutableList(KNOBS) { i ->
            if (i >= arr.length()) return@MutableList defaultKnob(i)      // anciennes sauvegardes à 32 boutons
            val o = arr.getJSONObject(i)
            KnobConfig(
                o.optInt("ch", 1).coerceIn(1, 16), o.optInt("cc", 7).coerceIn(0, 127),
                o.optInt("min", 0).coerceIn(0, 127), o.optInt("max", 127).coerceIn(0, 127),
                o.optString("label", "").take(24), o.optInt("value", 0).coerceIn(0, 127)
            )
        }
    }

    fun saveKnobs(ctx: Context, knobs: List<KnobConfig>) =
        prefs(ctx).edit().putString("knobs", knobsToJson(knobs).toString()).apply()

    fun loadKnobs(ctx: Context): MutableList<KnobConfig> {
        val s = prefs(ctx).getString("knobs", null) ?: return defaultKnobs()
        return try { knobsFromJson(JSONArray(s)) ?: defaultKnobs() } catch (e: Exception) { defaultKnobs() }
    }

    fun saveFader(ctx: Context, pos: Float) = prefs(ctx).edit().putFloat("fader", pos).apply()
    fun loadFader(ctx: Context): Float = prefs(ctx).getFloat("fader", 0f).coerceIn(0f, 1f)
    fun saveFaderLatch(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("faderLatch", on).apply()
    fun loadFaderLatch(ctx: Context): Boolean = prefs(ctx).getBoolean("faderLatch", true)
    fun saveFaderForce(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("faderForce", on).apply()
    fun loadFaderForce(ctx: Context): Boolean = prefs(ctx).getBoolean("faderForce", false)

    // ------------------------------------------------------------ séquenceur
    /** Pas enregistrés de façon compacte : seuls les pas non vides sont écrits [index, on, note, vel, dur, chord, cond, ratch]. */
    private fun stepsToJson(steps: Array<Step>): JSONArray {
        val a = JSONArray()
        for (i in steps.indices) {
            val s = steps[i]
            if (s.isDefault()) continue
            a.put(JSONArray().put(i).put(if (s.on) 1 else 0).put(s.note).put(s.vel).put(s.dur).put(s.chord).put(s.cond).put(s.ratch)
                .put(s.pan).put(s.mod).put(s.bend).put(if (s.accent) 1 else 0).put(if (s.slide) 1 else 0)
                .put(s.extra?.let { x -> JSONArray().also { j -> x.forEach { j.put(it) } } } ?: JSONArray()))
        }
        return a
    }

    private fun stepsFromJson(a: JSONArray?, steps: Array<Step>) {
        steps.forEach { it.reset() }
        if (a == null) return
        for (k in 0 until a.length()) {
            val e = a.optJSONArray(k) ?: continue
            val i = e.optInt(0, -1)
            if (i !in 0 until MAX_STEPS) continue
            val s = steps[i]
            s.on = e.optInt(1) == 1
            s.note = e.optInt(2, 60).coerceIn(0, 127)
            s.vel = e.optInt(3, 100).coerceIn(1, 127)
            s.dur = e.optInt(4, Durs.DEFAULT).coerceIn(0, Durs.LAST)
            s.chord = e.optInt(5, 0).coerceIn(0, Chords.LAST)
            s.cond = e.optInt(6, 0).coerceIn(0, Conds.LAST)
            s.ratch = e.optInt(7, 1).coerceIn(1, 8)
            s.pan = e.optInt(8, -1).coerceIn(-1, 127)
            s.mod = e.optInt(9, -1).coerceIn(-1, 127)
            s.bend = e.optInt(10, -1).coerceIn(-1, 16383)
            s.accent = e.optInt(11, 0) == 1
            s.slide = e.optInt(12, 0) == 1
            val x = e.optJSONArray(13)
            if (x != null && x.length() in 1..11) {
                val iv = IntArray(x.length()) { x.optInt(it, 0).coerceIn(1, 127) }
                s.extra = iv
            }
        }
    }

    private fun seqToJson(seq: Sequencer): JSONObject {
        val o = JSONObject().put("bpm", seq.bpm).put("swing", seq.swing)
        val tracks = JSONArray()
        seq.tracks.forEach { t ->
            tracks.put(JSONObject()
                .put("name", t.name).put("ch", t.channel).put("len", t.length).put("div", t.div)
                .put("tr", t.transpose).put("mute", t.mute).put("dir", t.dir).put("page", t.page).put("start", t.start).put("solo", t.solo)
                .put("A", stepsToJson(t.patterns[0])).put("B", stepsToJson(t.patterns[1])))
        }
        o.put("v", VERSION)
        o.put("tracks", tracks)
        return o
    }

    private fun seqFromJson(o: JSONObject, seq: Sequencer) {
        val v = o.optInt("v", 1)
        seq.bpm = o.optInt("bpm", 120).coerceIn(30, 300)
        seq.swing = o.optInt("swing", 50).coerceIn(50, 75)
        val tracks = o.getJSONArray("tracks")
        for (ti in 0 until TRACKS) {
            val t = seq.tracks[ti]
            t.releaseRequest = true
            if (ti >= tracks.length()) {
                t.name = ""; t.channel = ti + 1; t.length = 16; t.div = 2; t.transpose = 0; t.mute = false; t.dir = 0; t.page = 0; t.start = 0; t.solo = -1
                t.patterns.forEach { p -> p.forEach { it.reset() } }
                continue
            }
            val to = tracks.getJSONObject(ti)
            t.name = to.optString("name", "").take(24)
            t.channel = to.optInt("ch", ti + 1).coerceIn(1, 16)
            t.length = to.optInt("len", 16).coerceIn(1, MAX_STEPS)
            t.div = to.optInt("div", 2).coerceIn(0, Divs.labels.size - 1)
            t.transpose = to.optInt("tr", 0).coerceIn(-1024, 1024)
            t.mute = to.optBoolean("mute", false)
            t.dir = to.optInt("dir", 0).coerceIn(0, 3)
            t.start = to.optInt("start", 0).coerceIn(0, t.length - 1)
            t.solo = to.optInt("solo", -1).coerceIn(-1, MAX_STEPS - 1)
            t.page = to.optInt("page", 0).coerceIn(0, 1)
            if (v >= 3) {
                stepsFromJson(to.optJSONArray("A"), t.patterns[0])
                stepsFromJson(to.optJSONArray("B"), t.patterns[1])
            } else {
                // anciennes sauvegardes : 32 pas complets [on, note, vel, dur|gate, chord]
                t.page = 0
                t.patterns[1].forEach { it.reset() }
                val st = t.patterns[0]
                st.forEach { it.reset() }
                val steps = to.optJSONArray("steps") ?: JSONArray()
                for (si in 0 until minOf(32, steps.length())) {
                    val a = steps.getJSONArray(si)
                    val s = st[si]
                    s.on = a.optInt(0) == 1
                    s.note = a.optInt(1, 60).coerceIn(0, 127)
                    s.vel = a.optInt(2, 100).coerceIn(1, 127)
                    if (v >= 2) {
                        val d = a.optInt(3, Durs.DEFAULT)
                        s.dur = if (d >= 20) Durs.LAST else d.coerceIn(0, Durs.LAST)   // ∞ était l'index 20
                        val c = a.optInt(4, 0)
                        s.chord = (if (c >= 3) c + 1 else c).coerceIn(0, Chords.LAST)   // PWR inséré à l'index 3
                    } else {
                        s.dur = Durs.nearest(a.optInt(3, 50))
                    }
                }
            }
        }
    }

    fun saveSeq(ctx: Context, seq: Sequencer) =
        prefs(ctx).edit().putString("seq", seqToJson(seq).toString()).apply()

    fun loadSeq(ctx: Context, seq: Sequencer) {
        val s = prefs(ctx).getString("seq", null) ?: return
        try { seqFromJson(JSONObject(s), seq) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ Destroy Mode
    private fun fxToJson(d: Destroyer): JSONObject {
        val armed = JSONArray(); d.armed.forEach { armed.put(it) }
        val pads = JSONArray()
        d.pads.forEach { pads.put(JSONObject().put("name", it.name).put("ch", it.channel).put("note", it.note).put("vel", it.vel)) }
        return JSONObject().put("mask", d.mask).put("armed", armed).put("pads", pads).put("multi", d.multi)
    }

    private fun fxFromJson(o: JSONObject, d: Destroyer) {
        d.mask = o.optInt("mask", 0xFFFF)
        d.multi = o.optBoolean("multi", false)
        o.optJSONArray("armed")?.let { a -> for (i in 0 until minOf(a.length(), Destroyer.COUNT)) d.armed[i] = a.optBoolean(i) }
        if (!d.multi) {   // hors MULTI : un seul effet
            val first = d.armed.indexOfFirst { it }.let { if (it < 0) 0 else it }
            for (i in 0 until Destroyer.COUNT) d.armed[i] = i == first
        }
        o.optJSONArray("pads")?.let { a ->
            for (i in 0 until minOf(a.length(), d.pads.size)) {
                val p = a.getJSONObject(i)
                d.pads[i] = PadConfig(p.optString("name", "PAD ${i + 1}").take(12), p.optInt("ch", 1).coerceIn(1, 16),
                    p.optInt("note", 36 + i).coerceIn(0, 127), p.optInt("vel", 110).coerceIn(1, 127))
            }
        }
    }

    fun saveFx(ctx: Context, d: Destroyer) = prefs(ctx).edit().putString("fx", fxToJson(d).toString()).apply()
    fun loadFx(ctx: Context, d: Destroyer) {
        val s = prefs(ctx).getString("fx", null) ?: return
        try { fxFromJson(JSONObject(s), d) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ fichier de sauvegarde complet
    fun exportAll(ctx: Context, seq: Sequencer, fx: Destroyer): String {
        val o = JSONObject()
            .put("app", "RKTRL").put("version", VERSION)
            .put("knobs", knobsToJson(loadKnobs(ctx)))
            .put("fader", loadFader(ctx).toDouble())
            .put("seq", seqToJson(seq))
            .put("settings", settingsToJson())
            .put("fx", fxToJson(fx))
            .put("lang", Lang.current)
            .put("faderLatch", loadFaderLatch(ctx))
        return o.toString()
    }

    /** Charge un fichier de sauvegarde RKTRL. Renvoie false si invalide. */
    fun importAll(ctx: Context, text: String, seq: Sequencer, fx: Destroyer): Boolean {
        return try {
            val o = JSONObject(text)
            val app = o.optString("app", "")
            if (app != "RKTRL") return false
            val knobs = knobsFromJson(o.getJSONArray("knobs")) ?: return false
            seqFromJson(o.getJSONObject("seq"), seq)
            o.optJSONObject("settings")?.let { settingsFromJson(it) }
            o.optJSONObject("fx")?.let { fxFromJson(it, fx) }
            saveKnobs(ctx, knobs)
            saveFader(ctx, o.optDouble("fader", 0.0).toFloat())
            saveSeq(ctx, seq)
            saveSettings(ctx)
            saveFx(ctx, fx)
            if (o.has("lang")) { Lang.current = o.optInt("lang", Lang.FR).coerceIn(0, 2); saveLang(ctx, Lang.current) }
            if (o.has("faderLatch")) saveFaderLatch(ctx, o.optBoolean("faderLatch", true))
            true
        } catch (e: Exception) {
            false
        }
    }
}
