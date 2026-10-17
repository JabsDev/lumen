package com.jabs.lumen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jabs.lumen.engine.ObjectSegmenter
import com.jabs.lumen.model.EditParams
import com.jabs.lumen.model.Mask
import com.jabs.lumen.model.MaskType
import kotlin.math.roundToInt

/** Transient UI state for the mask tool: what is selected, overlay on/off, brush erasing or not. */
data class MaskUiState(
    val selectedId: Long? = null,
    val overlay: Boolean = true,
    val erasing: Boolean = false,
)

@Composable
fun MaskTab(
    vm: LumenViewModel,
    params: EditParams,
    ui: MaskUiState,
    onUi: (MaskUiState) -> Unit,
) {
    val selected = params.masks.firstOrNull { it.id == ui.selectedId }

    Column(Modifier.fillMaxWidth()) {
        if (params.masks.isEmpty()) {
            Text(
                "Toque num tipo para criar a primeira máscara.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        params.masks.forEach { mask ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    vm.updateLive { p -> p.copy(masks = p.masks.map { if (it.id == mask.id) it.copy(enabled = !it.enabled) else it }) }
                    vm.commit()
                }) {
                    Icon(
                        if (mask.enabled) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = if (mask.enabled) "Desativar" else "Ativar",
                    )
                }
                FilterChip(
                    selected = mask.id == ui.selectedId,
                    onClick = { onUi(ui.copy(selectedId = if (ui.selectedId == mask.id) null else mask.id)) },
                    label = { Text(mask.displayName()) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    vm.updateLive { p -> p.copy(masks = p.masks.filterNot { it.id == mask.id }) }
                    vm.commit()
                    if (ui.selectedId == mask.id) onUi(ui.copy(selectedId = null))
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Excluir máscara")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddChip("Linear", MaskType.LINEAR, vm) { onUi(ui.copy(selectedId = it)) }
            AddChip("Radial", MaskType.RADIAL, vm) { onUi(ui.copy(selectedId = it)) }
            AddChip("Pincel", MaskType.BRUSH, vm) { onUi(ui.copy(selectedId = it)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddChip("Céu", MaskType.SKY, vm) { onUi(ui.copy(selectedId = it)) }
            AddChip("Objeto", MaskType.SELECT, vm, invert = false) { onUi(ui.copy(selectedId = it)) }
            AddChip("Fundo", MaskType.SELECT, vm, invert = true) { onUi(ui.copy(selectedId = it)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AddChip("Pessoa", MaskType.SUBJECT, vm) { onUi(ui.copy(selectedId = it)) }
        }

        if (selected != null) {
            MaskShapeControls(vm, selected, ui, onUi)
            MaskAdjustControls(vm, selected)
        }
    }
}

@Composable
private fun AddChip(label: String, type: MaskType, vm: LumenViewModel, invert: Boolean = false, onCreated: (Long) -> Unit) {
    AssistChip(
        onClick = {
            val id = System.currentTimeMillis()
            val mask = Mask(id = id, type = type, invert = invert)
            vm.updateLive { p -> p.copy(masks = p.masks + mask) }
            vm.commit()
            onCreated(id)
        },
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
    )
}

@Composable
private fun MaskShapeControls(vm: LumenViewModel, mask: Mask, ui: MaskUiState, onUi: (MaskUiState) -> Unit) {
    fun set(next: Mask) {
        vm.updateLive { p -> p.copy(masks = p.masks.map { if (it.id == mask.id) next else it }) }
    }

    fun commitSet(next: Mask) {
        set(next)
        vm.commit()
    }

    Text(
        mask.displayName(),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = ui.overlay,
            onClick = { onUi(ui.copy(overlay = !ui.overlay)) },
            label = { Text("Sobrepor") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = mask.invert,
            onClick = { commitSet(mask.copy(invert = !mask.invert)) },
            label = { Text("Inverter") },
        )
    }
    MAdjust(vm, "Intensidade", mask.opacity, 0f..1f, ::percent, { set(mask.copy(opacity = it)) })
    MAdjust(vm, "Difusão", mask.feather, 0f..1f, ::percent, { set(mask.copy(feather = it)) })

    when (mask.type) {
        MaskType.LINEAR -> {
            MAdjust(vm, "Ângulo", mask.angle, 0f..180f, { "${it.roundToInt()}°" }, { set(mask.copy(angle = it)) })
            MAdjust(vm, "Posição", mask.position, 0f..1f, ::percent, { set(mask.copy(position = it)) })
            Text(
                "Arraste na foto para mover a linha.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaskType.RADIAL -> {
            MAdjust(vm, "Largura", mask.rx, 0.05f..0.7f, ::percent, { set(mask.copy(rx = it)) })
            MAdjust(vm, "Altura", mask.ry, 0.05f..0.7f, ::percent, { set(mask.copy(ry = it)) })
            MAdjust(vm, "Rotação", mask.rotation, 0f..180f, { "${it.roundToInt()}°" }, { set(mask.copy(rotation = it)) })
            Text(
                "Arraste na foto para mover o centro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaskType.BRUSH -> {
            MAdjust(vm, "Tamanho", mask.brushSize, 0.01f..0.25f, ::percent, { set(mask.copy(brushSize = it)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = ui.erasing,
                    onClick = { onUi(ui.copy(erasing = !ui.erasing)) },
                    label = { Text("Apagar") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { commitSet(mask.copy(strokes = emptyList())) }) { Text("Limpar") }
            }
            Text(
                "Pinte com o dedo sobre a foto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaskType.SKY -> {
            Text(
                "Detecta o céu pela cor e pela suavidade da parte de cima. Use Difusão para suavizar a borda.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaskType.SELECT -> {
            // The neural model has no tolerance knob: it already returns the object's own edge.
            // The slider only matters on the classical fallback, so it is hidden when the model runs.
            if (!ObjectSegmenter.available) {
                MAdjust(vm, "Tolerância", mask.tolerance, 0f..0.5f, ::percent, { set(mask.copy(tolerance = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = ui.erasing,
                    onClick = { onUi(ui.copy(erasing = !ui.erasing)) },
                    label = { Text(if (ui.erasing) "Subtrair" else "Adicionar") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { commitSet(mask.copy(points = emptyList())) },
                    enabled = mask.points.isNotEmpty(),
                ) { Text("Limpar pontos") }
            }
            if (mask.points.isNotEmpty()) {
                Text(
                    "${mask.points.count { !it.subtract }} ponto(s) somando, ${mask.points.count { it.subtract }} subtraindo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    ui.erasing -> "Toque no que deve sair da seleção."
                    mask.invert -> "Toque no objeto; a máscara mantém o resto da foto (o fundo)."
                    ObjectSegmenter.available ->
                        "Toque no objeto: a IA acha o contorno dele. Toque mais vezes para somar partes."
                    else -> "Toque no objeto. Toque mais vezes para somar ou subtrair partes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaskType.SUBJECT -> {
            MAdjust(vm, "Tolerância", mask.tolerance, 0f..0.5f, ::percent, { set(mask.copy(tolerance = it)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = ui.erasing,
                    onClick = { onUi(ui.copy(erasing = !ui.erasing)) },
                    label = { Text(if (ui.erasing) "Subtrair" else "Adicionar") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { commitSet(mask.copy(points = emptyList())) },
                    enabled = mask.points.isNotEmpty(),
                ) { Text("Limpar pontos") }
            }
            if (mask.points.isNotEmpty()) {
                Text(
                    "${mask.points.count { !it.subtract }} ponto(s) somando, ${mask.points.count { it.subtract }} subtraindo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    ui.erasing -> "Toque no que não é pessoa para tirar da máscara."
                    mask.points.isEmpty() && mask.invert -> "Detecta a pessoa e mantém o resto da foto (o fundo)."
                    mask.points.isEmpty() -> "Detecta a pessoa na foto. Use Subtrair para tirar o que veio a mais."
                    else -> "Toque para somar mais partes da pessoa, ou use Subtrair para tirar o que não é."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun MaskAdjustControls(vm: LumenViewModel, mask: Mask) {
    val a = mask.adjust
    fun set(next: com.jabs.lumen.model.MaskAdjust) {
        vm.updateLive { p -> p.copy(masks = p.masks.map { if (it.id == mask.id) it.copy(adjust = next) else it }) }
    }

    fun commitSet(next: com.jabs.lumen.model.MaskAdjust) {
        set(next)
        vm.commit()
    }

    Text("Ajustes da máscara", style = MaterialTheme.typography.titleSmall)
    MAdjust(vm, "Exposição", a.exposure, -2f..2f, { "%+.2f EV".format(it) }, { set(a.copy(exposure = it)) })
    MAdjust(vm, "Contraste", a.contrast, -1f..1f, ::mpercent, { set(a.copy(contrast = it)) })
    MAdjust(vm, "Realces", a.highlights, -1f..1f, ::mpercent, { set(a.copy(highlights = it)) })
    MAdjust(vm, "Sombras", a.shadows, -1f..1f, ::mpercent, { set(a.copy(shadows = it)) })
    MAdjust(vm, "Brancos", a.whites, -1f..1f, ::mpercent, { set(a.copy(whites = it)) })
    MAdjust(vm, "Pretos", a.blacks, -1f..1f, ::mpercent, { set(a.copy(blacks = it)) })
    MAdjust(vm, "Temperatura", a.temperature, -1f..1f, ::mpercent, { set(a.copy(temperature = it)) })
    MAdjust(vm, "Matiz", a.tint, -1f..1f, ::mpercent, { set(a.copy(tint = it)) })
    MAdjust(vm, "Saturação", a.saturation, -1f..1f, ::mpercent, { set(a.copy(saturation = it)) })
}

@Composable
private fun MAdjust(
    vm: LumenViewModel,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
    default: Float = 0f,
) {
    AdjustSlider(
        label = label,
        value = value,
        range = range,
        display = display,
        onValueChange = { onChange(it) },
        onValueChangeFinished = { vm.commit() },
        onReset = {
            onChange(default)
            vm.commit()
        },
    )
}

private fun mpercent(v: Float): String = "%+d".format((v * 100f).roundToInt())

private fun percent(v: Float): String = "%d%%".format((v * 100f).roundToInt())
