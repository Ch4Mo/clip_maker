package com.clipmaker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipmaker.app.ui.theme.Palette
import com.clipmaker.core.model.ParamSpec
import kotlin.math.abs
import kotlin.math.roundToInt

/** Square toolbar button with icon + label (CapCut-style bottom bar). */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = Palette.TextPrimary,
) {
    val color = when {
        !enabled -> Palette.TextSecondary.copy(alpha = 0.4f)
        active -> Palette.Accent
        else -> tint
    }
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextSecondary,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Labelled slider with value readout and double-tap-to-reset. */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
    default: Float? = null,
    onChangeFinished: () -> Unit = {},
    format: (Float) -> String = { v -> formatValue(v, range) },
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                format(value) + if (unit.isNotEmpty()) " $unit" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (default != null && abs(value - default) > 1e-4f) Palette.Accent else Palette.TextSecondary,
                modifier = Modifier.clickable(enabled = default != null) { default?.let { onChange(it); onChangeFinished() } },
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Palette.Accent,
                inactiveTrackColor = Palette.SurfaceHighest,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun ParamSlider(spec: ParamSpec, value: Float, onChange: (Float) -> Unit, onChangeFinished: () -> Unit = {}) =
    ParamSlider(spec.label, value, spec.min..spec.max, onChange, unit = spec.unit, default = spec.default, onChangeFinished = onChangeFinished)

fun formatValue(v: Float, range: ClosedFloatingPointRange<Float>): String {
    val span = range.endInclusive - range.start
    return when {
        span >= 100 -> v.roundToInt().toString()
        span >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
}

/** Rounded selectable tile used for presets (filters, transitions, animations...). */
@Composable
fun PresetTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.SurfaceHighest,
    icon: ImageVector? = null,
    preview: (@Composable (Modifier) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .border(2.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) preview(Modifier.fillMaxSize())
            if (icon != null) Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center,
            color = if (selected) Palette.Accent else Palette.TextPrimary, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.Accent else Palette.SurfaceHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
fun PillRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
