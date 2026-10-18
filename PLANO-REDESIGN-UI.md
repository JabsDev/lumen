# Lumen — Plano de Redesign da Interface

> **Objetivo do documento:** guiar a reforma visual do app Lumen (editor de fotos Android,
> Jetpack Compose) para um layout **mais arejado, limpo e minimalista**, trocando o acento
> **azul por verde**, **sem remover nenhuma funcionalidade**.
>
> Este documento é autocontido: outro modelo de IA (ou pessoa desenvolvedora) deve conseguir
> executar o plano só com o que está aqui + o código do repositório.

---

## 1. Situação atual (diagnóstico)

Screenshots do estado atual estão em `docs/ui-atual/` (capturados no emulador, 1080×2400).
Referências usadas ao longo do plano:

| Arquivo | Tela |
|---|---|
| `04_library.png` | Biblioteca com fotos |
| `05_editor_luz.png` | Editor, aba Luz |
| `06_curvas.png` | Editor, aba Curvas |
| `07_cor.png` / `08_cor.png` | Editor, aba Cor / Efeitos |
| `10_mascara.png` | Editor, aba Máscara |
| `11_colapsado.png` | Editor com painel recolhido |
| `12_camera_remote.png` | Controle remoto da câmera |

### 1.1 Problemas confirmados (com evidências)

1. **Sensação de aperto (claustrofobia):**
   - Com o painel aberto, a foto fica espremida entre barra superior, linha de presets,
     painel e faixa de abas. Em `05_editor_luz.png` a foto ocupa ~25% da altura da tela
     (foto larga) e o empilhamento inferior tem **4 camadas** (presets → sliders → abas →
     gesture bar) com pouquíssimo respiro entre elas.
   - `AdjustSlider` usa `padding(vertical = 2.dp)` — sliders visualmente colados uns nos outros.
   - Painel de ajustes tem altura fixa de `215.dp` com padding horizontal de `16.dp`.
   - Grade da biblioteca: células com `spacedBy(4.dp)` e canto de `6.dp` — fotos coladas,
     sem "ar" (`04_library.png`).

2. **Poluição visual:**
   - Tudo tem **contorno**: presets são `AssistChip` com borda, abas são `FilterChip` com
     borda, chips de LUT com borda, chips de máscara com borda e ícone "+". São dezenas de
     retângulos contornados por tela (`05_editor_luz.png`, `10_mascara.png`).
   - A linha de presets aparece em **todas** as abas, mesmo quando não é relevante.
   - Barra superior do editor tem 5 ícones + nome de arquivo longo (`1000000019.jpg`)
     competindo por atenção (`05_editor_luz.png`).
   - Histograma é uma caixa preta pesada (`0x99000000`) de `168×52.dp` sobre a foto.
   - Na aba Máscara, 7 chips de criação em 3 linhas + lista + controles se acumulam sem
     hierarquia (`10_mascara.png`).

3. **Tema inconsistente (bugs de cor):**
   - O `Theme.kt` só define 8 roles do `darkColorScheme`. Roles não definidos caem no
     **roxo padrão do Material3** — por isso o FAB "Importar" está roxo (`04_library.png`).
   - Azul hardcoded fora do tema: overlay de seleção de fotos da câmera `Color(0x663A7BD5)`
     (`CameraScreen.kt`), ícone do launcher `#8AB4FF` (`ic_launcher_foreground.xml`).

### 1.2 Orçamento de layout atual (retrato, painel aberto, tela ≈ 914.dp de altura)

| Faixa | Altura aproximada |
|---|---|
| Status bar | 24.dp |
| Barra superior do editor | ~56.dp |
| **Área da foto (Box `weight(1f)`, padding 8.dp)** | **~481.dp (53%)** |
| Linha de presets | ~46.dp |
| Painel (215 + padding) | ~231.dp |
| Faixa de abas | ~52.dp |
| Navigation bar | 24.dp |

**Meta:** foto com ~520.dp (+8%), uma camada a menos no empilhamento inferior, e painéis
com mais respiro interno — sem perder acesso a nada.

---

## 2. Inventário funcional (nada disso pode regredir)

**Biblioteca** (`LibraryScreen.kt`): grade de fotos importadas; importar via Photo Picker
(FAB); importar arquivos RAW/DNG (ícone pasta); abrir câmera Lumix; verificação de
atualização (automática 1×/sessão + manual) com diálogo de estados
(`Checking/UpToDate/Available/Downloading/Ready/Failed`).

**Editor** (`EditorScreen.kt`): abrir/voltar; undo/redo; segurar o ícone "compare" para ver
o original (e long-press na foto faz o mesmo); exportar; histograma sobre a foto com toque
para alternar canais RGB↔só luminância; zoom 1–8× por pinça, duplo-toque 2.5×, pan com
clamp, badge de zoom que zera ao tocar; 6 presets; 7 abas (Luz, Curvas, Cor, Efeitos, Lente,
Geometria, Máscara) — tocar na aba ativa recolhe o painel; aba Curvas tem painel mais alto
sem scroll; "Redefinir tudo"; nome do arquivo no topo.

- **Luz**: 8 sliders (Exposição ±2EV, Contraste, Realces, Sombras, Brancos, Pretos, Clareza,
  Textura). Duplo-toque no slider reseta o valor.
- **Curvas**: seletor de canal (RGB/R/G/B), editor de curva por pontos (arrastar/criar),
  reset de canal.
- **Cor**: sub-abas Básico (Temperatura, Matiz, Saturação, Vibração), Gradação (3 rodas:
  sombras/meios-tons/altas luzes, reset), Mistura (8 bandas de cor, HSL por banda, reset) e
  botão "Confirmar ajustes de cor" (ele é o commit das rodas/mistura — **não remover**).
- **Efeitos**: lista de LUTs (.cube) com "Sem LUT"/importar/remover (com diálogo de
  confirmação), slider de Força da LUT, slider Vinheta.
- **Lente**: Distorção, Aberração Vermelho/Ciano, Aberração Azul/Amarelo.
- **Geometria**: Girar ↺, Girar ↻, Espelhar.
- **Máscara** (`MaskPanel.kt`): criar 7 tipos (Linear, Radial, Pincel, Céu, Objeto, Fundo,
  Pessoa); lista de máscaras com ativar/desativar, selecionar, excluir; por máscara:
  Sobrepor (overlay), Inverter, Intensidade, Difusão + controles específicos por tipo
  (ângulo/posição; largura/altura/rotação; tamanho de pincel/apagar/limpar; tolerância no
  fallback; adicionar/subtrair pontos); 9 sliders de ajuste da máscara. Na foto: pintar com
  o dedo, tocar para adicionar/subtrair pontos (Objeto/Fundo/Pessoa), arrastar linha/centro
  (Linear/Radial) — com zoom desabilitado enquanto a aba está aberta.

**Câmera** (`CameraScreen.kt`, `ControlScreen.kt`): live view; faixa de ajustes
(ISO/F/SS/EV/WB) que abre arcos radiais; disparo (toque) e abertura de todos os arcos
(arrastar do obturador); gravar vídeo; galeria da câmera com grade 3 colunas, seleção
múltipla (long-press), preview e importação com progresso; bateria; estados de conexão.

---

## 3. Direção de design

### 3.1 Princípios

1. **A foto manda.** Todo chrome (barras, chips, painéis) recua visualmente: sem bordas
   desnecessárias, sem ícones gritando, hierarquia por peso e espaço — não por contorno.
2. **Uma coisa de cada vez.** Cada faixa da tela tem um único papel. Nada de linha extra
   permanente (presets saem do caminho e voltam para dentro da aba Luz).
3. **Respiro mensurável.** Espaçamentos sobem para a escala 4/8/12/16/20; nada de `2.dp`
   ou `4.dp` como separação principal.
4. **Neutro no palco, verde na ação.** Fundos continuam cinza-neutros (fundamental para
   julgar cor ao editar). O verde entra em elementos interativos e estados selecionados.
5. **Zero perda funcional.** Tudo que existe continua acessível com no máximo 1 toque a
   mais (e na prática, quase tudo com o mesmo número de toques).

### 3.2 Paleta (tema escuro — o app só tem tema escuro)

Substituir o `LumenColors` em `app/src/main/java/com/jabs/lumen/ui/Theme.kt` por um esquema
**completo** (isso também conserta o FAB roxo — ele usa `primaryContainer`, hoje indefinido):

```kotlin
private val LumenColors = darkColorScheme(
    primary = Color(0xFF8FD8A8),            // verde-menta suave — acento principal
    onPrimary = Color(0xFF0B2B18),          // texto/ícone sobre o verde
    primaryContainer = Color(0xFF1F4A32),   // FAB e containers de destaque
    onPrimaryContainer = Color(0xFFCDEFDB),
    secondary = Color(0xFF9AB8A6),          // verde acinzentado — apoio discreto
    onSecondary = Color(0xFF10231A),
    secondaryContainer = Color(0xFF26352C), // fundo de chips/abas selecionados
    onSecondaryContainer = Color(0xFFD8E8DE),
    tertiary = Color(0xFFFFC978),           // mantém o âmbar para avisos pontuais
    onTertiary = Color(0xFF2B1D05),
    background = Color(0xFF0E0F11),         // inalterado — neutro
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF17191C),            // inalterado
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF23262B),     // inalterado — trilhos, placeholders
    onSurfaceVariant = Color(0xFFA7ADB4),   // texto secundário um pouco mais legível
    outline = Color(0xFF3A4048),            // bordas quando indispensáveis
    error = Color(0xFFE0615E),
)
```

**Regras de aplicação:**
- Fundos **neutros** (sem matiz verde) — crítico para não contaminar a percepção de cor da foto.
- Verde = ação/seleção (sliders, aba ativa, FAB, botões de confirmação, checks).
- Cores **semânticas de edição não mudam**: canais R/G/B do histograma (`0xFFE8615E`,
  `0xFF5FC46A`, `0xFF5B8FE6`), canais do seletor de curva (`CurveEditor.kt`), bolinhas do
  mixer (`BAND_COLORS` em `ColorMixerPanel.kt`), rodas de gradação. Azul nesses lugares
  representa o canal azul da imagem, não o tema.

**Azuis a eliminar (grep de verificação):**
- `#8AB4FF` → primária antiga (`Theme.kt`) **e** `android:fillColor="#8AB4FF"` em
  `app/src/main/res/drawable/ic_launcher_foreground.xml` (trocar por `#8FD8A8`).
- `Color(0x663A7BD5)` → overlay de seleção em `CameraScreen.kt` (`GalleryCell`) —
  trocar por `MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)`.

**Arquivos de recurso:** `res/values/themes.xml` e `res/values/colors.xml` usam `#FF0E0F11`
(janela/barras do sistema) — **manter**.

### 3.3 Tipografia

Manter a escala do Material3; mudar apenas **usos**:

| Uso | De | Para |
|---|---|---|
| Nome do arquivo no editor | `titleMedium` (branco) | `bodyMedium`, `onSurfaceVariant` |
| Cabeçalhos de seção nos painéis ("LUT", nome da máscara, "Ajustes da máscara") | `titleSmall` | `titleSmall` + `onSurfaceVariant`, com espaçamento de seção (ver §5.4) |
| Textos de ajuda das máscaras | `bodySmall`/`onSurfaceVariant` | manter |
| Rótulos de slider | `labelLarge` | manter |

### 3.4 Espaçamento e forma (tokens)

```
xs = 4.dp   sm = 8.dp   md = 12.dp   lg = 16.dp   xl = 20.dp   xxl = 24.dp
```

- Separação padrão entre elementos de uma lista: **md (12.dp)**.
- Padding lateral de telas: **lg (16.dp)**; de painéis do editor: **xl (20.dp)**.
- Raio de canto: células de grade e chips `6.dp` → **12.dp**; cards/caixas sobre a foto
  (histograma, badges) `6.dp` → **8.dp**.

---

## 4. Mudanças por arquivo

Ordem recomendada de execução = ordem das seções (§5 primeiro = tema, depois componentes,
telas, polimento). Ao fim de cada fase: compilar, instalar e capturar telas (§7).

---

### 5.1 Tema global

**`app/src/main/java/com/jabs/lumen/ui/Theme.kt`**
- Trocar o `darkColorScheme` pelo esquema completo da §3.2.
- Não adicionar tema claro (o app é escuro-only por decisão de design).

**`app/src/main/res/drawable/ic_launcher_foreground.xml`**
- `android:fillColor="#8AB4FF"` → `"#8FD8A8"` (o anel do ícone acompanha o novo acento).

**Efeito colateral esperado (bom):** FAB, diálogos (`AlertDialog`), `CircularProgressIndicator`
e sliders passam a usar verde automaticamente, sem tocar nas telas.

---

### 5.2 Componentes base — `app/src/main/java/com/jabs/lumen/ui/Components.kt`

**`AdjustSlider`** (usado por ~30 sliders do app — é a maior alavanca de "respiro"):
- `padding(vertical = 2.dp)` → `padding(vertical = 6.dp)`.
- Entre o `Row` de rótulos e o `Slider`: nada a adicionar (o M3 já reserva espaço).
- Não definir cores manualmente: o `Slider` do M3 já usa `primary` (trilho ativo + thumb)
  e `surfaceVariant` (trilho inativo) a partir do tema novo.
- **Manter intacta** a função `detectDoubleTap` (reset por duplo-toque) e o contrato
  `onValueChangeFinished` (commit no histórico).

**`Histogram`**:
- Em `EditorScreen.kt`: `HISTOGRAM_WIDTH 168.dp → 144.dp`, `HISTOGRAM_HEIGHT 52.dp → 46.dp`,
  `HISTOGRAM_MARGIN 10.dp → 8.dp`.
- Em `Components.kt`: fundo `Color(0x99000000)` → `Color(0x8017191C)` (usa a cor de
  surface com 50% de alpha — some mais sobre a foto), canto `6.dp → 8.dp`.
- Manter: toque alterna canais, silhueta de luminância + traços RGB (cores semânticas).

---

### 5.3 Tela do editor — `app/src/main/java/com/jabs/lumen/ui/EditorScreen.kt`

É o arquivo que mais muda. Estrutura final desejada (retrato):

```
┌──────────────────────────────┐
│ ←  arquivo.jpg    ↶ ↷ ▯ ⤓   │  barra superior (mais leve)
├──────────────────────────────┤
│                              │
│        FOTO (maior)          │  sem padding lateral; histograma menor
│                              │
├──────────────────────────────┤
│  (painel da aba, 228.dp)     │  presets agora vivem dentro da aba Luz
├──────────────────────────────┤
│  Luz Curvas Cor Efeitos …    │  abas sem borda, texto + verde na ativa
└──────────────────────────────┘
```

**a) Barra superior** (`Row` no início de `EditorScreen`):
- Manter os 5 ícones (voltar, desfazer, refazer, comparar, exportar) — todos funcionais.
- Título: `style = MaterialTheme.typography.bodyMedium`,
  `color = MaterialTheme.colorScheme.onSurfaceVariant` (hoje herda `titleMedium` branco).
- Padding da Row: `horizontal = 4.dp → 8.dp`.

**b) Área da foto** (`Box(Modifier.weight(1f)...)`):
- `.padding(8.dp)` → `.padding(horizontal = 0.dp, vertical = 4.dp)`.
- Badges "×2.5" e "ORIGINAL": fundo `Color(0x99000000)` →
  `MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)`, canto `4.dp → 8.dp`.
- **Não mexer** em: `transformState`, `clampOffset`, `toImageCoords`, `toImageSpace`,
  `oriented`, handlers de máscara, `LaunchedEffect` do overlay. (Gestos e geometria.)

**c) Presets entram na aba Luz** (remove uma faixa permanente da tela):
- **Apagar** o bloco `if (panelOpen) { LazyRow { items(Preset.entries) { AssistChip … } } }`.
- No `when (tab) { 0 -> … }`, inserir **antes** do primeiro `Adjust`:
  ```kotlin
  Text(
      "Predefinições",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
  )
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(Preset.entries) { preset ->
          AssistChip(onClick = { vm.applyPreset(preset) }, label = { Text(preset.label) })
      }
  }
  Spacer(Modifier.height(12.dp))
  ```
- Como o painel tem scroll vertical, os 8 sliders continuam acessíveis rolando.
- Racional: preset é "ponto de partida", não ajuste contínuo — fica onde o fluxo começa.

**d) Painel da aba**:
- Alturas: `.height(if (curvesTab) 300.dp else 215.dp)` →
  `.height(if (curvesTab) 304.dp else 228.dp)`.
- Padding: `horizontal = 16.dp → 20.dp`, `vertical = 8.dp → 12.dp`.
- "Redefinir tudo": alinhar à direita e acalmar a cor —
  ```kotlin
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      TextButton(
          onClick = vm::resetAll,
          colors = ButtonDefaults.textButtonColors(
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
          ),
      ) { Text("Redefinir tudo") }
  }
  ```

**e) Faixa de abas** (a maior fonte de "ruído de contorno"):
- Substituir os `FilterChip` por um composable local sem borda:
  ```kotlin
  @Composable
  private fun CategoryTab(title: String, selected: Boolean, onClick: () -> Unit) {
      Text(
          title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable(onClick = onClick)
              .padding(horizontal = 14.dp, vertical = 12.dp),
      )
  }
  ```
- O `LazyRow` final vira:
  ```kotlin
  LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
      itemsIndexed(CATEGORIES) { i, title ->
          CategoryTab(
              title = title,
              selected = panelOpen && tab == i,
              onClick = {
                  if (panelOpen && tab == i) panelOpen = false
                  else { tab = i; panelOpen = true }
              },
          )
      }
  }
  ```
- Comportamento de recolher ao tocar a aba ativa **permanece idêntico**.
- `import androidx.compose.material3.FilterChip` pode sobrar — limpar imports mortos.

---

### 5.4 Aba Cor + painéis auxiliares

**Sub-abas de Cor** (`ColorTab` em `EditorScreen.kt`) — BOM 2024.09.00 ⇒ M3 1.3, tem
`SegmentedButton` estável:
- Trocar a `Row` de `AssistChip` (e os helpers `chipActive()`/`chipIdle()`, que devem ser
  **apagados**) por:
  ```kotlin
  SingleChoiceSegmentedButtonRow(
      Modifier.fillMaxWidth().padding(vertical = 4.dp),
  ) {
      listOf("Básico", "Gradação", "Mistura").forEachIndexed { i, label ->
          SegmentedButton(
              selected = sub == i,
              onClick = { sub = i },
              shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
          ) { Text(label) }
      }
  }
  ```
- **Manter** o `TextButton("Confirmar ajustes de cor")` — é ele quem faz `vm.commit()` das
  rodas e da mistura. Só suavizar:
  `colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)`
  (já é o padrão; apenas não remover).

**`ColorGradingPanel.kt`**: fundo das rodas `0xFF14161A` manter (neutro). Apenas respiro:
`padding(top = 14.dp) → 18.dp` entre a roda de cima e a dupla de baixo; o `TextButton
"Redefinir"` permanece.

**`ColorMixerPanel.kt`**: bolinhas de banda `22/18.dp → 24/20.dp` (alvo de toque melhor),
espaçamento da fileira `10.dp → 12.dp`. Cores das bandas intactas.

**`CurveEditor.kt`**: a linha da curva usa `colorScheme.primary` → vira verde sozinha.
Manter fundo `0xFF14161A` e a grade `0xFF343A42` (neutros). Não tocar em `editAt`,
`GRAB_RADIUS` nem no seletor de canais (cores semânticas).

---

### 5.5 Painel de máscaras — `app/src/main/java/com/jabs/lumen/ui/MaskPanel.kt`

Objetivo: hierarquia clara em 3 blocos — **lista → criar → controles da selecionada** —
sem remover nenhum chip ou slider.

a) **Lista de máscaras** (quando existirem):
- Trocar os ícones de checkbox por visibilidade (material-icons-extended já é dependência):
  `Icons.Filled.Visibility` / `Icons.Outlined.VisibilityOff` — mesma ação (toggle `enabled`).
- O `FilterChip` do nome permanece (o tema novo já o deixa verde-escuro sem borda pesada).
  Opcional e preferível: `FilterChip(..., border = null)` para remover o contorno.
- Ícone de excluir: `tint = MaterialTheme.colorScheme.onSurfaceVariant`.

b) **Bloco de criação** — substituir as 3 `Row` manuais por um fluxo único:
```kotlin
Text(
    "Criar máscara",
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
)
FlowRow(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    AddChip("Linear", MaskType.LINEAR, vm) { onUi(ui.copy(selectedId = it)) }
    AddChip("Radial", MaskType.RADIAL, vm) { onUi(ui.copy(selectedId = it)) }
    AddChip("Pincel", MaskType.BRUSH, vm) { onUi(ui.copy(selectedId = it)) }
    AddChip("Céu", MaskType.SKY, vm) { onUi(ui.copy(selectedId = it)) }
    AddChip("Objeto", MaskType.SELECT, vm, invert = false) { onUi(ui.copy(selectedId = it)) }
    AddChip("Fundo", MaskType.SELECT, vm, invert = true) { onUi(ui.copy(selectedId = it)) }
    AddChip("Pessoa", MaskType.SUBJECT, vm) { onUi(ui.copy(selectedId = it)) }
}
```
(`FlowRow` está em `androidx.compose.foundation.layout`, estável no BOM 2024.09.00.)

c) **Controles da máscara selecionada** (`MaskShapeControls`/`MaskAdjustControls`):
- Cabeçalhos com respiro: `Modifier.padding(top = 16.dp, bottom = 4.dp)` nos `titleSmall`
  (nome da máscara e "Ajustes da máscara").
- Manter **todos** os sliders e textos de ajuda por tipo (são a documentação viva da
  ferramenta) e toda a lógica (`ObjectSegmenter.available`, contagem de pontos, etc.).
- Chips "Sobrepor"/"Inverter"/"Apagar"/"Subtrair": manter como `FilterChip` (tema resolve
  o visual); espaçamento entre eles `8.dp → 12.dp`.
- `Spacer(Modifier.height(4.dp))` final → `Spacer(Modifier.height(12.dp))`.

---

### 5.6 Biblioteca — `app/src/main/java/com/jabs/lumen/ui/LibraryScreen.kt`

a) **Grade** (`LazyVerticalGrid`):
- `GridCells.Adaptive(120.dp)` → `Adaptive(140.dp)` (menos colunas, células maiores).
- `spacedBy(4.dp)` → `spacedBy(10.dp)` (horizontal e vertical).
- `contentPadding`: laterais `4.dp → 16.dp`; manter `bottom = padding + 88.dp` (folga do FAB).
- Célula (`Thumbnail`): canto `6.dp → 12.dp`.

b) **Barra superior**: 3 ícones hoje (atualização, câmera, pasta). Mover a verificação de
atualização para um menu overflow, deixando 2 ícones no ar:
```kotlin
var menu by remember { mutableStateOf(false) }
Box {
    IconButton(onClick = { menu = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções")
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text("Verificar atualizações") },
            onClick = { menu = false; vm.checkForUpdates(manual = true) },
        )
    }
}
IconButton(onClick = vm::openCamera) { … }
IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { … }
```
Manter `LaunchedEffect(Unit) { vm.checkForUpdates() }` (checagem silenciosa por sessão) e o
`UpdateDialog` sem alterações de lógica.

c) **Estado vazio**: manter o texto; adicionar acima dele um
`Icon(Icons.Outlined.PhotoLibrary, …, modifier = Modifier.size(56.dp),
tint = MaterialTheme.colorScheme.onSurfaceVariant)` com `Spacer(16.dp)` — preenche o vazio
sem poluir.

d) **FAB**: nenhuma mudança de código — o tema novo o torna verde (`primaryContainer`).

---

### 5.7 Câmera — `CameraScreen.kt` e `ControlScreen.kt`

`ControlScreen.kt` (remoto com arcos radiais) já é minimalista por desenho (HUD branco sobre
preto, tudo some quando não usado). **Não mudar layout, geometria (`ArcGeometry`,
`geometryFor`, `ringAt`) nem gestos.** Único toque opcional: o texto de status usa
`Color.White.copy(alpha = 0.55f)` — manter.

`CameraScreen.kt` (lista de arquivos da câmera):
- Grade: `spacedBy(4.dp) → 8.dp`, canto da célula `6.dp → 12.dp`.
- `GalleryCell`: overlay de selecionado `Color(0x663A7BD5)` →
  `MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)`. O check já usa
  `colorScheme.primary` (fica verde sozinho).
- Botões "Cancelar"/"Importar N": manter posições; o `Button` herda o verde do tema.
- `PreviewPane`: barra inferior com progresso/`Button("Editar")` — manter, apenas padding
  `16.dp → 20.dp` se quiser respiro extra.

---

## 6. Restrições (o que NÃO tocar)

1. **Nada de `engine/`, `model/`, `data/`, `camera/`** — este redesign é 100% camada `ui/`
   + recursos de tema/ícone.
2. **Gestos e geometria do editor**: zoom/pan/clamp, duplo-toque, long-press "original",
   `toImageCoords/toImageSpace/oriented`, e todo o pipeline de overlay de máscara
   (`LaunchedEffect` rasterizado, `maskGeom`, `handleMaskPoint`).
3. **`detectDoubleTap`** dos sliders (reset) — incluindo o comentário explicativo.
4. **Lógica de estado**: `panelOpen`, `tab`, `MASK_TAB`, `maskUi`, commits do ViewModel
   (`updateLive`/`commit`), `BackHandler`s.
5. **Cores semânticas de edição** (canais RGB, bandas do mixer, rodas de gradação) e os
   fundos neutros `0xFF14161A` das rodas/curva.
6. **`ControlScreen.kt`** inteiro, exceto se surgir conflito de tema (não deve — ele não
   usa o `colorScheme` para nada além do herdado por `Text` padrão; mesmo assim, revisar
   screenshot depois).
7. **README.md**: se alguma descrição de interface ficar desatualizada (ex.: presets só na
   aba Luz), ajustar o trecho correspondente ao final.

---

## 7. Ambiente de build e verificação

**Ferramentas locais:**
- JDK 21: `/home/jabs/tools/jdk-21` (exportar `JAVA_HOME`).
- Android SDK: `/home/jabs/Android/Sdk` (já referenciado em `local.properties`).
- AVD de teste: `lumen_test` (Android 35, google_apis, x86_64, perfil Pixel 6).

**Subir o emulador (sem janela, para screenshots via adb):**
```bash
ANDROID_AVD_HOME=/home/jabs/.config/.android/avd \
  ~/Android/Sdk/emulator/emulator -avd lumen_test \
  -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-save &
adb wait-for-device
```

**Compilar para o emulador (precisa da ABI x86_64; o release normal é arm64-only):**
```bash
cd /home/jabs/projects/lumen
JAVA_HOME=/home/jabs/tools/jdk-21 ./gradlew :app:assembleDebug -PlumenAbis=arm64-v8a,x86_64
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(A primeira compilação baixa dependências do Gradle — leva alguns minutos.)

**Laço de verificação visual por fase:**
```bash
adb shell monkey -p com.jabs.lumen -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > /tmp/opencode/depois_<tela>.png   # e ler o PNG
# navegação: adb shell input tap X Y   (tela 1080x2400), keyevent 4 = voltar
```
Comparar com os "antes" em `docs/ui-atual/`.

**Testes instrumentados** (só se algo de máscara/segmentação for tocado — não deve ser o caso):
```bash
JAVA_HOME=/home/jabs/tools/jdk-21 ./gradlew :app:connectedDebugAndroidTest -PlumenAbis=x86_64
```

---

## 8. Roteiro de execução (fases)

| Fase | Arquivos | Verificação |
|---|---|---|
| **1 — Tema** | `Theme.kt`, `ic_launcher_foreground.xml` | Compila; FAB verde; diálogo de atualização com botões verdes; ícone verde |
| **2 — Componentes** | `Components.kt` | Sliders com mais respiro; duplo-toque reseta; histograma menor/mais translúcido |
| **3 — Editor** | `EditorScreen.kt` | Presets dentro da aba Luz; abas sem borda; painel 228/304; foto maior; zoom/pan/máscara intactos |
| **4 — Painéis** | `MaskPanel.kt`, `ColorTab` (em `EditorScreen.kt`), `ColorGradingPanel.kt`, `ColorMixerPanel.kt`, `CurveEditor.kt` | SegmentedButton nas sub-abas de Cor; FlowRow nos chips de máscara; todas as 7 máscaras criam/editam |
| **5 — Biblioteca e câmera** | `LibraryScreen.kt`, `CameraScreen.kt` | Grade arejada; overflow "Verificar atualizações" funciona; overlay de seleção verde |
| **6 — Regressão final** | — | Checklist §9 completo + screenshots "depois" |

---

## 9. Checklist de aceite

**Cor:**
- [ ] `grep -rn "8AB4FF" app/src` → 0 ocorrências.
- [ ] `grep -rn "3A7BD5" app/src` → 0 ocorrências.
- [ ] Cores semânticas preservadas: `5B8FE6` (histograma), `4E86E8` (curva), `4A84E8`
      (banda azul do mixer) continuam no código.
- [ ] FAB verde (screenshot da biblioteca).
- [ ] Aba ativa, sliders, checks e botões de confirmação em verde.

**Arejamento:**
- [ ] Grade da biblioteca com espaçamento visível entre células e cantos de 12.dp.
- [ ] Editor: nenhuma faixa de presets fora da aba Luz; faixa de abas sem bordas.
- [ ] Área da foto ≥ ~510.dp de altura com o painel aberto (medir: antes ~481.dp).
- [ ] Sliders com separação vertical visível (6.dp+).

**Funcional (regressão zero):**
- [ ] As 7 abas abrem; tocar na ativa recolhe o painel; foto cresce.
- [ ] Luz: 8 sliders + presets aplicam; duplo-toque reseta slider.
- [ ] Curvas: cria/arrasta pontos; 4 canais; reset de canal.
- [ ] Cor: 3 sub-abas; rodas arrastam; mistura por banda; "Confirmar ajustes de cor" commita.
- [ ] Efeitos: trocar LUT, ajustar força, importar e remover (diálogo) funcionam.
- [ ] Lente e Geometria: sliders e os 3 botões (girar ×2, espelhar) funcionam.
- [ ] Máscara: criar os 7 tipos; overlay liga/desliga; inverter; pintar pincel; pontos de
      objeto/fundo/pessoa (adicionar/subtrair); 9 ajustes da máscara; excluir máscara.
- [ ] Histograma alterna canais ao toque.
- [ ] Zoom por pinça (badge aparece e zera ao tocar), duplo-toque 2.5×, pan, long-press
      "ORIGINAL", botão comparar por pressão.
- [ ] Undo/redo, exportar (toast), voltar para a biblioteca.
- [ ] Biblioteca: importar fotos e arquivos; menu ⋮ → verificar atualizações; abrir câmera.
- [ ] Câmera: tela remota abre (mesmo sem câmera física, estado "não conectada" renderiza);
      lista de arquivos renderiza grade com novo espaçamento.

**Documentação:**
- [ ] Screenshots "depois" salvos (ex.: `docs/ui-depois/`) espelhando os de `docs/ui-atual/`.
- [ ] `README.md` atualizado onde a descrição da interface mudou (presets na aba Luz).
