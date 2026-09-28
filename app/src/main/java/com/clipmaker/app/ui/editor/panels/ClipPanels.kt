package com.clipmaker.app.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clipmaker.app.ui.components.ParamSlider
import com.clipmaker.app.ui.components.Pill
import com.clipmaker.app.ui.components.PresetTile
import com.clipmaker.app.ui.components.SectionTitle
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.color.ColorLooks
import com.clipmaker.core.color.Rgb
import com.clipmaker.core.model.AnimatedFloat
import com.clipmaker.core.model.Clip
import com.clipmaker.core.model.ColorLookId
import com.clipmaker.core.model.Transition
import com.clipmaker.core.model.TransitionType
import com.clipmaker.core.model.VideoEffect
import com.clipmaker.core.model.VideoEffectCategory
import com.clipmaker.core.model.VideoEffectType
import kotlin.math.ln
import kotlin.math.pow

/** Signature used by all clip panels to apply an undoable change to the selected clip. */
typealias ClipUpdater = (label: String, coalesce: String?, update: (Clip) -> Clip) -> Unit

@Composable
fun SpeedPanel(clip: Clip, onSpeed: (Float, String?) -> Unit, onCommit: () -> Unit) {
    Column {
        // Logarithmic slider: -1..1 maps to 0.1x..10x.
        val pos = (ln(clip.speed.toDouble()) / ln(10.0)).toFloat()
        ParamSlider(
            "Vitesse", pos, -1f..1f,
            onChange = { onSpeed(10.0.pow(it.toDouble()).toFloat().let { s -> (s * 100).toInt() / 100f }, "speed") },
            onChangeFinished = onCommit,
            format = { "%.2fx".format(10.0.pow(it.toDouble())) },
        )
        SectionTitle("Préréglages")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.25f to "0.25x", 0.5f to "Ralenti 0.5x", 1f to "Normal", 1.5f to "1.5x", 2f to "2x", 4f to "4x", 8f to "Timelapse").forEach { (s, l) ->
                Pill(l, clip.speed == s, { onSpeed(s, null) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Durée : ${"%.2f".format(clip.durationUs / 1e6)} s · Le son est accéléré/ralenti avec préservation de la hauteur.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
        )
    }
}

@Composable
fun VolumePanel(clip: Clip, playheadLocalUs: Long, update: ClipUpdater, onCommit: () -> Unit) {
    Column {
        KeyframedSlider(
            label = "Volume", value = clip.volume, localUs = playheadLocalUs, range = 0f..2f, unit = "",
            format = { "${(it * 100).toInt()} %" },
            onValue = { a -> update("Volume", "volume") { it.copy(volume = a) } },
            onCommit = onCommit,
        )
        ParamSlider("Fondu d'entrée", clip.fadeInUs / 1e6f, 0f..(clip.durationUs / 2e6f).coerceAtLeast(0.1f),
            { v -> update("Fondu", "fadein") { it.copy(fadeInUs = (v * 1e6).toLong()) } }, unit = "s", default = 0f, onChangeFinished = onCommit)
        ParamSlider("Fondu de sortie", clip.fadeOutUs / 1e6f, 0f..(clip.durationUs / 2e6f).coerceAtLeast(0.1f),
            { v -> update("Fondu", "fadeout") { it.copy(fadeOutUs = (v * 1e6).toLong()) } }, unit = "s", default = 0f, onChangeFinished = onCommit)
    }
}

/** Slider bound to an [AnimatedFloat] with a keyframe toggle at the playhead (the ◆ button). */
@Composable
fun KeyframedSlider(
    label: String,
    value: AnimatedFloat,
    localUs: Long,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValue: (AnimatedFloat) -> Unit,
    onCommit: () -> Unit,
    default: Float? = null,
    format: ((Float) -> String)? = null,
) {
    val hasKey = value.hasKeyframeNear(localUs)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (format != null) {
                ParamSlider(label, value.valueAt(localUs), range, { onValue(value.set(localUs, it)) }, unit = unit, default = default, onChangeFinished = onCommit, format = format)
            } else {
                ParamSlider(label, value.valueAt(localUs), range, { onValue(value.set(localUs, it)) }, unit = unit, default = default, onChangeFinished = onCommit)
            }
        }
        IconButton(onClick = {
            onValue(if (hasKey) value.withoutKeyframeNear(localUs) else value.withKeyframe(localUs, value.valueAt(localUs)))
            onCommit()
        }) {
            Icon(
                if (hasKey) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Image clé",
                tint = if (hasKey) Palette.Amber else if (value.keyframes.isNotEmpty()) Palette.Amber.copy(alpha = 0.6f) else Palette.TextSecondary,
            )
        }
    }
}

@Composable
fun FiltersPanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    val current = clip.videoEffects.firstOrNull { it.type == VideoEffectType.LOOK }
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                PresetTile("Aucun", current == null, onClick = {
                    update("Filtre", null) { c -> c.copy(videoEffects = c.videoEffects.filterNot { it.type == VideoEffectType.LOOK }) }
                }, icon = Icons.Default.Block)
            }
            items(ColorLookId.entries) { look ->
                PresetTile(
                    look.label, current?.look == look,
                    onClick = {
                        update("Filtre ${look.label}", null) { c ->
                            val others = c.videoEffects.filterNot { it.type == VideoEffectType.LOOK }
                            c.copy(videoEffects = listOf(VideoEffect(VideoEffectType.LOOK, look = look)) + others)
                        }
                    },
                    preview = { m -> LookSwatch(look, m) },
                )
            }
        }
        if (current != null) {
            ParamSlider(
                "Intensité", current.param("intensity"), 0f..1f,
                { v ->
                    update("Intensité", "look-intensity") { c ->
                        c.copy(videoEffects = c.videoEffects.map { if (it.type == VideoEffectType.LOOK) it.copy(params = it.params + ("intensity" to v)) else it })
                    }
                },
                default = 1f, onChangeFinished = onCommit,
            )
        }
        Text("Astuce : appliquez le même filtre à tous les plans pour une image homogène.", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

/** Swatch preview of a look applied to a fixed palette of colours. */
@Composable
fun LookSwatch(look: ColorLookId, modifier: Modifier = Modifier) {
    val samples = listOf(Rgb(0.85f, 0.6f, 0.45f), Rgb(0.2f, 0.45f, 0.7f), Rgb(0.35f, 0.6f, 0.3f), Rgb(0.95f, 0.9f, 0.8f))
    val colors = samples.map { val c = ColorLooks.apply(look, it); Color(c.r, c.g, c.b) }
    Box(modifier.background(Brush.linearGradient(colors)))
}

@Composable
fun AdjustPanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    val grade = clip.videoEffects.firstOrNull { it.type == VideoEffectType.COLOR_GRADE } ?: VideoEffect(VideoEffectType.COLOR_GRADE)
    Column {
        VideoEffectType.COLOR_GRADE.params.forEach { spec ->
            ParamSlider(spec, grade.param(spec.key), onChange = { v ->
                update("Réglage ${spec.label}", "grade-${spec.key}") { c ->
                    val existing = c.videoEffects.firstOrNull { it.type == VideoEffectType.COLOR_GRADE }
                    val updated = (existing ?: VideoEffect(VideoEffectType.COLOR_GRADE)).let { it.copy(params = it.params + (spec.key to v)) }
                    c.copy(videoEffects = if (existing == null) c.videoEffects + updated else c.videoEffects.map { if (it.type == VideoEffectType.COLOR_GRADE) updated else it })
                }
            }, onChangeFinished = onCommit)
        }
        Pill("Réinitialiser", false, {
            update("Réinitialiser", null) { c -> c.copy(videoEffects = c.videoEffects.filterNot { it.type == VideoEffectType.COLOR_GRADE }) }
        })
    }
}

@Composable
fun EffectsPanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    var category by remember { mutableStateOf(VideoEffectCategory.STYLIZE) }
    val categories = listOf(VideoEffectCategory.STYLIZE, VideoEffectCategory.DISTORT, VideoEffectCategory.FILTER, VideoEffectCategory.ADJUST, VideoEffectCategory.KEYING)
    val available = VideoEffectType.entries.filter { it.category == category && it != VideoEffectType.LOOK && it != VideoEffectType.COLOR_GRADE }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { c -> Pill(c.label, c == category, { category = c }) }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(available) { type ->
                val active = clip.videoEffects.any { it.type == type }
                PresetTile(type.label, active, onClick = {
                    update(if (active) "Retirer ${type.label}" else "Effet ${type.label}", null) { c ->
                        if (active) c.copy(videoEffects = c.videoEffects.filterNot { it.type == type })
                        else c.copy(videoEffects = c.videoEffects + VideoEffect(type))
                    }
                }, icon = Icons.Default.AutoAwesome, color = if (active) Palette.AccentSoft else Palette.SurfaceHighest)
            }
        }
        clip.videoEffects.filter { it.type != VideoEffectType.LOOK && it.type != VideoEffectType.COLOR_GRADE }.forEach { effect ->
            SectionTitle(effect.type.label)
            if (effect.type.params.isEmpty()) Text("Aucun réglage", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            effect.type.params.forEach { spec ->
                ParamSlider(spec, effect.param(spec.key), onChange = { v ->
                    update(effect.type.label, "fx-${effect.type}-${spec.key}") { c ->
                        c.copy(videoEffects = c.videoEffects.map { if (it.type == effect.type) it.copy(params = it.params + (spec.key to v)) else it })
                    }
                }, onChangeFinished = onCommit)
            }
        }
        if (clip.videoEffects.any { it.type == VideoEffectType.CHROMA_KEY }) {
            Text("Placez ce clip sur une piste d'incrustation pour voir la vidéo principale derrière.", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

@Composable
fun TransformPanel(clip: Clip, playheadLocalUs: Long, update: ClipUpdater, onCommit: () -> Unit) {
    val t = clip.transform
    Column {
        KeyframedSlider("Position X", t.x, playheadLocalUs, -1f..1f, "", { a -> update("Position", "tx") { it.copy(transform = it.transform.copy(x = a)) } }, onCommit, default = 0f)
        KeyframedSlider("Position Y", t.y, playheadLocalUs, -1f..1f, "", { a -> update("Position", "ty") { it.copy(transform = it.transform.copy(y = a)) } }, onCommit, default = 0f)
        KeyframedSlider("Échelle", t.scale, playheadLocalUs, 0.1f..4f, "x", { a -> update("Échelle", "ts") { it.copy(transform = it.transform.copy(scale = a)) } }, onCommit, default = 1f)
        KeyframedSlider("Rotation", t.rotationDeg, playheadLocalUs, -180f..180f, "°", { a -> update("Rotation", "tr") { it.copy(transform = it.transform.copy(rotationDeg = a)) } }, onCommit, default = 0f)
        KeyframedSlider("Opacité", t.opacity, playheadLocalUs, 0f..1f, "", { a -> update("Opacité", "to") { it.copy(transform = it.transform.copy(opacity = a)) } }, onCommit, default = 1f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remplir le cadre", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(t.fill, { v -> update("Cadrage", null) { it.copy(transform = it.transform.copy(fill = v)) } },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent))
            IconButton(onClick = { update("Miroir", null) { it.copy(transform = it.transform.copy(flipHorizontal = !it.transform.flipHorizontal)) } }) {
                Icon(Icons.Default.Flip, "Miroir", tint = if (t.flipHorizontal) Palette.Accent else Palette.TextSecondary)
            }
        }
        SectionTitle("Animations rapides")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = clip.durationUs
            Pill("Zoom lent (Ken Burns)", false, {
                update("Ken Burns", null) { it.copy(transform = it.transform.copy(scale = AnimatedFloat(1f).withKeyframe(0, 1f).withKeyframe(d, 1.2f))) }
            })
            Pill("Zoom punch", false, {
                update("Zoom punch", null) {
                    it.copy(transform = it.transform.copy(scale = AnimatedFloat(1f).withKeyframe(0, 1.25f, com.clipmaker.core.model.Easing.EASE_OUT).withKeyframe(minOf(d, 250_000), 1f)))
                }
            })
            Pill("Travelling →", false, {
                update("Travelling", null) {
                    it.copy(transform = it.transform.copy(scale = AnimatedFloat(1.15f), x = AnimatedFloat(0f).withKeyframe(0, -0.06f).withKeyframe(d, 0.06f)))
                }
            })
            Pill("Incrustation coin", false, {
                update("Incrustation", null) { it.copy(transform = it.transform.copy(scale = AnimatedFloat(0.35f), x = AnimatedFloat(0.3f), y = AnimatedFloat(0.28f))) }
            })
            Pill("Tout effacer", false, { update("Réinitialiser", null) { it.copy(transform = com.clipmaker.core.model.Transform()) } })
        }
    }
}

@Composable
fun TransitionPanel(clip: Clip, update: ClipUpdater, onCommit: () -> Unit) {
    var incoming by remember { mutableStateOf(true) }
    val current = if (incoming) clip.transitionIn else clip.transitionOut
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Entrée", incoming, { incoming = true })
            Pill("Sortie", !incoming, { incoming = false })
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                PresetTile("Aucune", current == null, onClick = {
                    update("Transition", null) { if (incoming) it.copy(transitionIn = null) else it.copy(transitionOut = null) }
                }, icon = Icons.Default.Block)
            }
            items(TransitionType.entries) { type ->
                PresetTile(type.label, current?.type == type, onClick = {
                    update("Transition ${type.label}", null) {
                        val tr = Transition(type, current?.durationUs ?: 500_000)
                        if (incoming) it.copy(transitionIn = tr) else it.copy(transitionOut = tr)
                    }
                }, icon = Icons.Default.AutoAwesome)
            }
        }
        if (current != null) {
            ParamSlider("Durée", current.durationUs / 1e6f, 0.1f..(clip.durationUs / 1e6f / 2).coerceAtLeast(0.2f), { v ->
                update("Durée transition", "tr-duration") {
                    val tr = current.copy(durationUs = (v * 1e6).toLong())
                    if (incoming) it.copy(transitionIn = tr) else it.copy(transitionOut = tr)
                }
            }, unit = "s", onChangeFinished = onCommit)
        }
        Text(
            "Astuce : une sortie « Zoom avant » suivie d'une entrée « Zoom arrière » sur le plan suivant donne une transition zoom fluide.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
        )
    }
}

@Composable
fun ColorSwatches(selected: Long, onSelect: (Long) -> Unit, includeTransparent: Boolean = false) {
    val colors = buildList {
        if (includeTransparent) add(0x00000000L)
        addAll(listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF4D8D, 0xFF7C5CFF, 0xFF22D3EE, 0xFF34D399, 0xFFFFC53D, 0xFFFF7A00, 0xFFE11D48, 0xFF3B82F6, 0xCC000000))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (c == 0L) Palette.SurfaceHighest else Color(c))
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (c == selected) Box(Modifier.size(10.dp).clip(CircleShape).background(if (c == 0xFFFFFFFFL) Color.Black else Color.White))
                if (c == 0L) Icon(Icons.Default.Block, null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
