package com.jabs.lumen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jabs.lumen.model.BandAdjust
import com.jabs.lumen.model.ColorMixer
import com.jabs.lumen.model.MixerBand
import kotlin.math.roundToInt

val BAND_COLORS = mapOf(
    MixerBand.RED to Color(0xFFE24B4B),
    MixerBand.ORANGE to Color(0xFFE8862E),
    MixerBand.YELLOW to Color(0xFFE5D033),
    MixerBand.GREEN to Color(0xFF4FB95C),
    MixerBand.AQUA to Color(0xFF3FC0C8),
    MixerBand.BLUE to Color(0xFF4A84E8),
    MixerBand.PURPLE to Color(0xFF9A5FD0),
    MixerBand.MAGENTA to Color(0xFFD84CA6),
)

@Composable
fun ColorMixerPanel(
    mixer: ColorMixer,
    selected: MixerBand,
    onSelectBand: (MixerBand) -> Unit,
    onChange: (MixerBand, BandAdjust) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adjust = mixer[selected]
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MixerBand.entries.forEach { band ->
                val active = band == selected
                Box(
                    Modifier
                        .size(if (active) 24.dp else 20.dp)
                        .background(BAND_COLORS.getValue(band), CircleShape)
                        .border(if (active) 2.dp else 1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        .clickable { onSelectBand(band) }
                )
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            MixSlider("Matiz", adjust.hue, { onChange(selected, adjust.copy(hue = it)) })
            MixSlider("Saturação", adjust.saturation, { onChange(selected, adjust.copy(saturation = it)) })
            MixSlider("Luminosidade", adjust.luminance, { onChange(selected, adjust.copy(luminance = it)) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReset) { Text("Redefinir") }
        }
    }
}

@Composable
private fun MixSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(10.dp)
                .background(Color(0xFF6B7280), CircleShape)
        )
        AdjustSlider(
            label = label,
            value = value,
            range = -1f..1f,
            display = { "%+d".format((it * 100f).roundToInt()) },
            onValueChange = onChange,
            onValueChangeFinished = {},
            onReset = { onChange(0f) },
        )
    }
}