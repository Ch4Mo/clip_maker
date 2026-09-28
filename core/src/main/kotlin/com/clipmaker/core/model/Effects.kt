package com.clipmaker.core.model

import kotlinx.serialization.Serializable

/** Description of a tweakable effect parameter, used to build generic editing panels. */
data class ParamSpec(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
)

@Serializable
enum class VideoEffectCategory(val label: String) {
    ADJUST("Réglages"),
    FILTER("Filtres"),
    STYLIZE("Styles"),
    DISTORT("Distorsion"),
    KEYING("Incrustation"),
}

@Serializable
enum class VideoEffectType(
    val label: String,
    val category: VideoEffectCategory,
    val params: List<ParamSpec>,
) {
    COLOR_GRADE(
        "Étalonnage", VideoEffectCategory.ADJUST,
        listOf(
            ParamSpec("exposure", "Exposition", -2f, 2f, 0f, "EV"),
            ParamSpec("contrast", "Contraste", -1f, 1f, 0f),
            ParamSpec("saturation", "Saturation", -1f, 1f, 0f),
            ParamSpec("vibrance", "Vibrance", -1f, 1f, 0f),
            ParamSpec("temperature", "Température", -1f, 1f, 0f),
            ParamSpec("tint", "Teinte", -1f, 1f, 0f),
            ParamSpec("hue", "Teinte globale", -180f, 180f, 0f, "°"),
            ParamSpec("highlights", "Hautes lumières", -1f, 1f, 0f),
            ParamSpec("shadows", "Ombres", -1f, 1f, 0f),
            ParamSpec("fade", "Délavé", 0f, 1f, 0f),
        ),
    ),
    LOOK(
        "Filtre", VideoEffectCategory.FILTER,
        listOf(ParamSpec("intensity", "Intensité", 0f, 1f, 1f)),
    ),
    VIGNETTE(
        "Vignette", VideoEffectCategory.STYLIZE,
        listOf(ParamSpec("amount", "Force", 0f, 1f, 0.5f), ParamSpec("size", "Taille", 0.1f, 1f, 0.6f)),
    ),
    GRAIN("Grain film", VideoEffectCategory.STYLIZE, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.3f))),
    BLUR("Flou", VideoEffectCategory.STYLIZE, listOf(ParamSpec("radius", "Rayon", 0f, 1f, 0.3f))),
    SHARPEN("Netteté", VideoEffectCategory.ADJUST, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.4f))),
    BLACK_AND_WHITE("Noir & blanc", VideoEffectCategory.FILTER, listOf(ParamSpec("intensity", "Intensité", 0f, 1f, 1f))),
    INVERT("Négatif", VideoEffectCategory.STYLIZE, emptyList()),
    GLITCH(
        "Glitch", VideoEffectCategory.DISTORT,
        listOf(ParamSpec("amount", "Force", 0f, 1f, 0.5f), ParamSpec("speed", "Vitesse", 0.1f, 4f, 1f)),
    ),
    VHS("VHS", VideoEffectCategory.STYLIZE, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.6f))),
    RGB_SPLIT("Décalage RVB", VideoEffectCategory.DISTORT, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.4f))),
    PIXELATE("Pixelisation", VideoEffectCategory.DISTORT, listOf(ParamSpec("size", "Taille", 0f, 1f, 0.3f))),
    MIRROR("Miroir", VideoEffectCategory.DISTORT, listOf(ParamSpec("mode", "Mode", 0f, 3f, 0f))),
    KALEIDOSCOPE("Kaléidoscope", VideoEffectCategory.DISTORT, listOf(ParamSpec("segments", "Segments", 2f, 12f, 6f))),
    WAVE("Ondulation", VideoEffectCategory.DISTORT, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.3f), ParamSpec("speed", "Vitesse", 0.1f, 4f, 1f))),
    ZOOM_PULSE(
        "Pulsation (beat)", VideoEffectCategory.DISTORT,
        listOf(ParamSpec("amount", "Force", 0f, 1f, 0.3f), ParamSpec("bpm", "BPM", 40f, 220f, 120f)),
    ),
    SHAKE("Secousse caméra", VideoEffectCategory.DISTORT, listOf(ParamSpec("amount", "Force", 0f, 1f, 0.4f), ParamSpec("speed", "Vitesse", 0.1f, 4f, 1f))),
    STROBE("Stroboscope", VideoEffectCategory.STYLIZE, listOf(ParamSpec("rate", "Fréquence", 1f, 20f, 8f, "Hz"))),
    LETTERBOX("Bandes cinéma", VideoEffectCategory.STYLIZE, listOf(ParamSpec("ratio", "Ratio", 1.5f, 2.8f, 2.39f))),
    CHROMA_KEY(
        "Fond vert", VideoEffectCategory.KEYING,
        listOf(
            ParamSpec("hue", "Couleur", 0f, 360f, 120f, "°"),
            ParamSpec("tolerance", "Tolérance", 0f, 1f, 0.3f),
            ParamSpec("softness", "Adoucissement", 0f, 1f, 0.1f),
        ),
    ),
    ;

    fun defaults(): Map<String, Float> = params.associate { it.key to it.default }
}

@Serializable
data class VideoEffect(
    val type: VideoEffectType,
    val params: Map<String, Float> = type.defaults(),
    /** Name of the colour look (for [VideoEffectType.LOOK]). */
    val look: ColorLookId? = null,
    val enabled: Boolean = true,
) {
    fun param(key: String): Float =
        params[key] ?: type.params.firstOrNull { it.key == key }?.default ?: 0f
}

@Serializable
enum class ColorLookId(val label: String) {
    CINEMATIC("Cinéma"),
    TEAL_ORANGE("Teal & Orange"),
    VINTAGE("Vintage"),
    NOIR("Noir"),
    WARM("Chaud"),
    COOL("Froid"),
    FADED("Délavé"),
    VIVID("Éclatant"),
    MATRIX("Matrix"),
    SUNSET("Coucher de soleil"),
    BLEACH_BYPASS("Bleach bypass"),
    CYBERPUNK("Cyberpunk"),
    KODAK("Pellicule chaude"),
    MOODY("Sombre"),
}

@Serializable
enum class AudioEffectType(val label: String, val params: List<ParamSpec>) {
    GAIN("Gain", listOf(ParamSpec("db", "Gain", -24f, 24f, 0f, "dB"))),
    EQUALIZER(
        "Égaliseur", listOf(
            ParamSpec("low", "Graves", -15f, 15f, 0f, "dB"),
            ParamSpec("lowMid", "Bas médiums", -15f, 15f, 0f, "dB"),
            ParamSpec("mid", "Médiums", -15f, 15f, 0f, "dB"),
            ParamSpec("highMid", "Hauts médiums", -15f, 15f, 0f, "dB"),
            ParamSpec("high", "Aigus", -15f, 15f, 0f, "dB"),
        ),
    ),
    LOW_PASS("Passe-bas", listOf(ParamSpec("cutoff", "Fréquence", 80f, 20_000f, 2_000f, "Hz"), ParamSpec("q", "Résonance", 0.3f, 10f, 0.707f))),
    HIGH_PASS("Passe-haut", listOf(ParamSpec("cutoff", "Fréquence", 20f, 8_000f, 200f, "Hz"), ParamSpec("q", "Résonance", 0.3f, 10f, 0.707f))),
    COMPRESSOR(
        "Compresseur", listOf(
            ParamSpec("threshold", "Seuil", -60f, 0f, -18f, "dB"),
            ParamSpec("ratio", "Ratio", 1f, 20f, 4f, ":1"),
            ParamSpec("attack", "Attaque", 0.1f, 100f, 10f, "ms"),
            ParamSpec("release", "Relâchement", 10f, 1_000f, 120f, "ms"),
            ParamSpec("makeup", "Gain", 0f, 24f, 4f, "dB"),
        ),
    ),
    REVERB(
        "Réverbération", listOf(
            ParamSpec("room", "Taille", 0f, 1f, 0.6f),
            ParamSpec("damping", "Amortissement", 0f, 1f, 0.4f),
            ParamSpec("mix", "Mix", 0f, 1f, 0.3f),
        ),
    ),
    DELAY(
        "Écho", listOf(
            ParamSpec("time", "Temps", 20f, 2_000f, 350f, "ms"),
            ParamSpec("feedback", "Répétitions", 0f, 0.95f, 0.4f),
            ParamSpec("mix", "Mix", 0f, 1f, 0.3f),
        ),
    ),
    CHORUS(
        "Chorus", listOf(
            ParamSpec("rate", "Vitesse", 0.05f, 5f, 0.8f, "Hz"),
            ParamSpec("depth", "Profondeur", 0f, 1f, 0.5f),
            ParamSpec("mix", "Mix", 0f, 1f, 0.5f),
        ),
    ),
    DISTORTION("Distorsion", listOf(ParamSpec("drive", "Saturation", 0f, 1f, 0.4f), ParamSpec("mix", "Mix", 0f, 1f, 1f))),
    BITCRUSHER("Lo-fi", listOf(ParamSpec("bits", "Bits", 2f, 16f, 8f), ParamSpec("downsample", "Sous-échantillonnage", 1f, 32f, 4f))),
    NOISE_GATE("Noise gate", listOf(ParamSpec("threshold", "Seuil", -80f, 0f, -45f, "dB"))),
    TELEPHONE("Téléphone / Radio", emptyList()),
    PITCH("Hauteur (pitch)", listOf(ParamSpec("semitones", "Demi-tons", -12f, 12f, 0f, "st"))),
    ;

    fun defaults(): Map<String, Float> = params.associate { it.key to it.default }
}

@Serializable
data class AudioEffect(
    val type: AudioEffectType,
    val params: Map<String, Float> = type.defaults(),
    val enabled: Boolean = true,
) {
    fun param(key: String): Float =
        params[key] ?: type.params.firstOrNull { it.key == key }?.default ?: 0f
}
