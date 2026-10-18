# Lumen — Plano: prévia em alta resolução, Nitidez/Ruído e Aberração cromática criativa

> **Objetivo do documento:** descrever, com diagnóstico e desenho técnico, como resolver três
> pedidos feitos em cima do app em uso:
>
> 1. **A prévia nunca volta a alta resolução** — sem isso, não dá para comparar antes/depois.
> 2. **Faltam Nitidez (sharpen) e Redução de ruído** no painel de ajustes.
> 3. **A Aberração cromática só remove** — deveria permitir **adicionar** (efeito criativo).
>
> **Este documento não altera código.** Ele é a especificação para a implementação (feita por
> outra sessão/modelo), no mesmo espírito de `PLANO-REDESIGN-UI.md`.
>
> Referências de código são `arquivo:linha` no estado atual do repositório (commit `1a109f4`).

---

## 1. Resumo executivo (o que será feito)

| # | Pedido | Causa-raiz encontrada | Solução proposta (resumo) |
|---|---|---|---|
| 1 | Prévia presa em baixa resolução | (a) o "full render" aterrissa em no máximo **1400 px** (`DeviceProfile.initialPreviewSize`), muito abaixo da foto nativa; (b) **alguns painéis nunca chamam `commit()`**, então o `settleRender()` nunca dispara e a tela fica com a cópia de arrasto (520–900 px); (c) não existe nenhum nível de **detalhe 1:1** | Prévia em **3 níveis** (arrasto → tela → detalhe 1:1) + commit/settle universal + um render de **detalhe sob demanda** e um modo de comparação real |
| 2 | Faltam Nitidez e Redução de ruído | Não existem no modelo nem no `ImageEngine` (busca por `nitidez/ruído/denoise/sharpen` não encontra nada) | Novos parâmetros `sharpness`, `noiseLuminance`, `noiseColor` (+ `noiseDetail`), implementados como passes de luminância/croma reaproveitando o `boxBlur` já existente, com UI numa aba **Detalhe** |
| 3 | Aberração cromática só remove | O controle **já é bidirecional** (`−1..1`, `escala = 1 + v·0,004`), mas o rótulo, a força e a falta de prévia 1:1 fazem parecer só correção | Reetiquetar com direção explícita, curva de força própria, **deslocamento cromático não radial** (a "adição" criativa de verdade) e presets |

**Frase que amarra os três pedidos:** *a nitidez e a redução de ruído só fazem sentido se a
prévia puder mostrar o pixel real.* Hoje ela não pode — e é por isso que ajustar nitidez no
Lumen seria adivinhar. O item 1 é pré-requisito de percepção dos itens 2 e 3.

---

## 2. Diagnóstico detalhado

### 2.1 Arquitetura de prévia hoje

O caminho é todo em CPU sobre `Bitmap` (`android.graphics`), sem shaders/GPU.

```
open(photo)                       LumenViewModel.kt:219-255
  └─ ImageEngine.decode(file, previewSize)      → _original      (não editado)
  └─ dragSource = _original escalado a dragSize                 (cópia pequena)

scheduleRender(fast=true)         LumenViewModel.kt:767-800
  └─ src = dragSource ?: _original
  └─ _preview = ImageEngine.renderFast(src, params)             (520–900 px)

commit() → settleRender()         LumenViewModel.kt:274-287 / 854-866
  └─ espera SETTLE_DELAY_MS = 180 ms
  └─ scheduleRender(fast=false)
       └─ src = _original
       └─ _preview = renderFast(_original, params)              (≤ 1400 px)

exportCurrent()                   LumenViewModel.kt:384-398
  └─ ImageEngine.decode(file, null)  → resolução NATIVA
  └─ render(full, params) + JPEG → Pictures/Lumen
```

Ou seja: **a única vez que o app renderiza em resolução nativa é na exportação.** Tudo o que o
usuário vê (inclusive o "antes" do compara) fica limitado a `previewSize`.

### 2.2 Evidências concretas (por que a prévia não sobe)

**(a) O teto do "full render" é baixo.**

`app/src/main/java/com/jabs/lumen/engine/DeviceProfile.kt:30`

```kotlin
fun initialPreviewSize(dragSize: Int): Int = (dragSize * 1.7f).roundToInt().coerceIn(880, 1400)
```

`dragSize` fica entre 520 e 900 (`DeviceProfile.kt:13-27`), então o settled preview vive em
**880–1400 px no maior lado**. Numa foto de 12 MP (4000×3000) isso é ~1,3 MP renderizados de
12 MP: **~9× menos pixels**. Fotos de celular atual (48–50 MP) chegam a ~30× menos.

Consequência direta: dar pinça até 8× (`EditorScreen.kt:108`, `MAX_ZOOM = 8f`) faz zoom em cima
de uma imagem já reduzida — o resultado é borrado por construção. E `graphicsLayer`
(`EditorScreen.kt:341-346`) só **escala** o bitmap pequeno; não há re-render por zoom.

**(b) Dois painéis nunca disparam o render de tela — bug que explica o "fica o tempo todo".**

O render grande só acontece dentro de `settleRender()`, e este só é chamado por `commit()`
(`LumenViewModel.kt:285-286`). Quem chama `commit()` ao soltar o dedo:

| Painel | Soltar o dedo commita? | Efeito |
|---|---|---|
| Sliders de ajuste (`Adjust` → `AdjustSlider`) | **Sim** — `onValueChangeFinished = vm::commit` (`EditorScreen.kt:711`) | volta a 1400 px após 180 ms |
| Curva (`CurveEditor`) | **Sim** — `onCommit = vm::commit` (`EditorScreen.kt:561`) | OK |
| Máscara (`MAdjust`) | **Sim** — `MaskPanel.kt:333` | OK |
| **Mistura de cores** (`ColorMixerPanel`) | **NÃO** — `onValueChangeFinished = {}` (`ColorMixerPanel.kt:93`) | fica preso na cópia de arrasto até apertar "Confirmar ajustes de cor" |
| **Gradação** (rodas) | **NÃO** — só `onChange`, sem commit ao soltar (`ColorGradingPanel.kt:130-148`) | idem |

Sem `commit()`, `pendingFullRender` continua `true` e `settleRender()` nunca roda: a imagem
**fica mesmo em baixa** (520–900 px) enquanto o usuário acha que terminou o ajuste. Isso é um
defeito objetivo, alinhado com a percepção "fica em baixa resolução o tempo todo".

**(c) Não existe nível de detalhe.** Não há nenhum `Bitmap` em resolução nativa em memória
durante a edição, nem recorte por viewport, nem "1:1". O compara mostra `_original`
(`EditorScreen.kt:130`, `shown = if (showOriginal) original else preview`), que também é
`previewSize` — logo o "antes" e o "depois" têm o mesmo teto.

### 2.3 Comparação antes/depois hoje

- Segurar o ícone Compare (`EditorScreen.kt:160-173`) ou long-press na foto
  (`EditorScreen.kt:331`) troca `preview` por `original`.
- É um "peek" do original **não editado**, no mesmo `previewSize`, com a etiqueta `ORIGINAL`
  (`EditorScreen.kt:381-392`).
- Não há comparação **lado a lado** nem controle deslizante de divisão.
- Com zoom, ambos borram igual — a comparação perde justamente o que interessa avaliar
  (nitidez, ruído, franja cromática).

### 2.4 Nitidez e redução de ruído: inexistentes

Busca no repositório por `nitidez|ruído|denoise|sharpen|sharpness|noise` → **nenhum resultado**.
Não existem campos em `EditParams` (`model/EditParams.kt:5-32`), não aparecem em
`isToneIdentity` (`engine/ImageEngine.kt:548-552`) e não há UI.

O que **já existe** e deve ser reaproveitado:

- `boxBlur(src: FloatArray, w, h, r)` — blur separável O(n) (`ImageEngine.kt:573-597`).
- `lumaSrc` (luminância da fonte) e os "bases" de clareza/textura (`ImageEngine.kt:202-213`).
- O ponto de aplicação de clareza/textura (`ImageEngine.kt:265-271`), que é exatamente o
  padrão de "unsharp mask" que a nitidez usa.
- Toda a infraestrutura de paralelismo (`runPixels`, `ImageEngine.kt:60-93`).

Nota importante: `MaskAdjust` documenta explicitamente que clareza/textura ficam **globais**
"porque precisam de vizinhança" (`model/Masks.kt:65-66`). Nitidez e ruído também precisam
de vizinhança → por coerência, **ficam globais** (ver §6.5).

### 2.5 Aberração cromática: já permite adicionar, mas não comunica

`ImageEngine.kt:496-528`:

```kotlin
val redScale  = 1f + p.chromaRed  * 0.004f
val blueScale = 1f + p.chromaBlue * 0.004f
// amostra em (cx + dx·f·redScale, cy + dy·f·redScale) e idem para azul
```

A UI expõe `-1f..1f` (`EditorScreen.kt:471-472`), ou seja **−100 % a +100 %**. Então:

- Valores **negativos** encolhem o canal (correção) e **positivos** ampliam (cria franja) —
  o modelo **já é bidirecional**.
- O que faz parecer "só remoção": os rótulos só nomeiam o defeito
  (`"Aberração Vermelho/Ciano"`), ficam na aba **"Lente"** (nome de correção), não há dica de
  sinal, a força máxima é discreta (±0,4 % de escala radial) e — de novo — **a prévia reduzida
  torna o efeito quase invisível.**
- Não há componente **não radial** (deslocamento uniforme de canal), que é o que produz a
  franja "criativa"/retrô mais reconhecível.

---

## 3. Requisitos funcionais (o que precisa ser verdade ao final)

**R1 — Prévia em alta resolução.** Em algum momento determinístico e visível ao usuário, a
imagem exibida deve chegar à resolução nativa (1:1) do arquivo, sem travar a UI.

**R2 — Antes/depois confiável.** Deve existir comparação em 1:1 dos dois lados (original e
editado), com divisão arrastável e/ou peek, ambas na mesma resolução.

**R3 — Nitidez.** Slider global que aumenta a nitidez percebida, com halo controlado.

**R4 — Redução de ruído.** Dois controles independentes — **luminância** e **cor** — com um
terceiro de "preservação de detalhe" para não plastificar.

**R5 — Aberração cromática add+remove.** O usuário consegue tanto corrigir (reduzir) quanto
**adicionar** franja, com rótulos que deixem claro o sentido, força suficiente para efeito
criativo e um controle de franja uniforme.

**R6 — Nada regride.** Exportação, máscaras, LUTs, curvas, câmera Lumix e o redesign já feito
continuam funcionando. JSON de fotos antigas continua carregando.

---

## 4. Arquitetura proposta — prévia em 3 níveis

### 4.1 Visão geral

| Nível | Nome | Resolução | Quando é produzido | Para que serve |
|---|---|---|---|---|
| L0 | `dragSource` | 520–900 px (existente) | ao vivo, durante o gesto | resposta instantânea do slider |
| L1 | `screenSource` | resolução da **tela** (ex.: 1080–2560 px no maior lado) | ~180 ms depois de soltar (`settleRender`) | visão "ajustada" fiel, sem borrar no fit |
| L2 | `detailSource` | **recorte nativo** do que está visível | sob demanda (botão 1:1, compara, zoom > limiar) | julgar nitidez, ruído e franja; comparar antes/depois |

Hoje só existem L0 e L1 (e L1 com teto de 1400). O plano completa L1 e cria L2.

### 4.2 L1 — "tela", não "1400 px fixos"

Trocar o teto fixo por resolução de tela + folga de zoom, com guarda de memória.

`DeviceProfile.kt` — substituir `initialPreviewSize`:

```kotlin
/**
 * Resolução do preview parado: cobre a tela com folga para a pinça sem re-render,
 * limitada por um orçamento de memória (o pipeline aloca ~6 cópias de trabalho).
 */
fun screenPreviewSize(screenLongSide: Int, heapMb: Int): Int {
    val desired = (screenLongSide * 1.5f).roundToInt()      // zoom até ~1,5× ainda nítido
    val budgetSide = sqrt((heapMb * 1024L * 1024L / BYTES_PER_PIXEL) / 3.0).toInt()
    return desired.coerceAtMost(budgetSide).coerceIn(1024, 3840)
}
```

Onde `screenLongSide` vem de `resources.displayMetrics` (ex.: 1080×2400 → 2400).
`BYTES_PER_PIXEL = 8` é a estimativa conservadora do pico (entrada + saída + 6 buffers
de trabalho; `applyAll` usa `pixels` `IntArray`, `lumaSrc` `FloatArray`, até 4 `FloatArray`
de blur e o `out` `Bitmap` — `ImageEngine.kt:183-213, 384-386`).

> **Por que não jogar direto para a nativa:** 12 MP × 8 bytes ≈ 96 MB de pico **só de trabalho**,
> mais fonte e saída. O `AndroidManifest.xml` **não** declara `largeHeap`
> (`app/src/main/AndroidManifest.xml:11-17`), então o heap típico (192–512 MB) estoura. É essa
> restrição que justifica L2 ser **recorte**, não imagem inteira.

### 4.3 L2 — detalhe 1:1 por viewport (o coração do pedido 1)

Em vez de renderizar a foto inteira, renderizar **só o retângulo visível**, em escala nativa.

```
                 ┌──────────────────────── box (tela) ────────────────────────┐
                 │  escala S, deslocamento P, ContentScale.Fit               │
   nativo  ──►   │  viewport nativo = f(S, P, boxSize, dims da imagem)        │
 (4000×3000)     │  recorte ~ 1080×1920 px nativos  (~2 MP)                   │
                 │  render do recorte (guardando o offset)                    │
                 └────────────────────────────────────────────────────────────┘
```

Passos:

1. **Calcular o viewport** em pixels de imagem, a partir do estado de zoom que hoje vive em
   `EditorScreen` (`scale`, `panOffset`, `boxSize`; `EditorScreen.kt:183-197`). A matemática
   precisa espelhar `ContentScale.Fit` + `graphicsLayer` de `EditorScreen.kt:341-346`
   (já existe uma implementação equivalente em `clampOffset`, `EditorScreen.kt:780-799`, e em
   `toImageCoords`, `EditorScreen.kt:722-739` — reaproveitar a mesma convenção).
2. **Decodificar/cortar a região** (novo `ImageEngine.decodeRegion`):
   - JPEG/PNG: `BitmapRegionDecoder` (decodifica só o retângulo, barato e sem OOM).
   - HEIC/WebP: `ImageDecoder` não corta → decodificar a nativa inteira uma vez e **cachear**
     (`detailBase`), cortando a seguir. Guarda de memória: se não couber, capar a nativa
     (ex.: 4000 px) e seguir com L2 "capado" (ainda muito melhor que hoje).
   - RAW: `RawDecoder.decode` já existe (`engine/RawDecoder.kt`); cortar do resultado.
3. **Renderizar** o recorte com `ImageEngine.render(crop, params)` e desenhar sobreposto,
   alinhado pela transformação inversa do viewport.
4. **Cachear** o último viewport (LRU de 1–2) e **invalidar** a cada `commit()` (ou marcar como
   "sujo" e só recomputar quando o usuário voltar a olhar 1:1).
5. **Debounce** durante a pinça: só recomputar quando o gesto parar (~150 ms), como já se faz
   com `settleRender` (`LumenViewModel.kt:854-866`).

**Disparadores** (qualquer um):
- botão **"1:1"** junto ao indicador de zoom (`EditorScreen.kt:365-380`);
- **segurar para comparar** (`EditorScreen.kt:160-173, 331`);
- zoom além de `DETAIL_ZOOM_THRESHOLD ≈ 1,5×`.

**Feedback**: enquanto L2 computa, mostrar um selo discreto "nítido…"/"1:1" sobre a foto
(padrão já usado para `ORIGINAL`, `EditorScreen.kt:381-392`), e um selo **"detalhe 1:1"** quando
estiver pronto, para o usuário saber que aquele é o pixel real.

**Alternativa mais simples (fallback / Fase 1):** renderizar a imagem inteira na nativa com
teto de orçamento — entrega "1:1 capado" (ex.: 2–4 MP) sem a complexidade do viewport.
Recomendação: **L2 por viewport** (é o que entrega 1:1 de verdade em 12–50 MP), com o teto de
orçamento como rede de segurança.

### 4.4 Orçamento de memória e alívio de GC

- Centralizar em `DeviceProfile` um `MemoryBudget` (heap, memória da classe, teto de pixels de
  detalhe).
- **Pool de buffers** para `IntArray`/`FloatArray` reutilizados por tamanho, para não gerar
  lixo a cada render (hoje cada `applyAll` aloca `IntArray(w*h)` inteiro — `ImageEngine.kt:183`).
- **`largeHeap="true"`**: avaliar com cuidado (não resolve tudo, muda a percepção de consumo);
  se adotado, usar só como folga combinada com o teto.
- Considerar `Bitmap.Config.HARDWARE` **apenas para a cópia de exibição** (a versão de
  visualização pode ser hardware; a de trabalho precisa ser legível por `getPixels`, então
  continua `ARGB_8888`). Marcar como otimização opcional.

### 4.5 Correção do bug de "settle" (mudança pequena, efeito grande)

Independente de L2, fazer todo gesto de edição terminar em `settleRender()`:

- `ColorMixerPanel.MixSlider` → `onValueChangeFinished = onCommit` (`ColorMixerPanel.kt:93`).
- Rodas de gradação → `onCommit` no `detectDragGestures(onDragEnd=…)` (`ColorGradingPanel.kt:43-48`).
- Alternativa mais robusta, à prova de esquecimento: em `updateLive`/`scheduleRender(fast=true)`,
  agendar o settle por **inatividade** (um `Job` reiniciado a cada evento); assim *qualquer*
  chamada de `updateLive` ganha o render de tela depois de N ms parada. Isso elimina a classe
  inteira de bugs ("esqueci de commitar") e é o caminho recomendado.

---

## 5. Mudanças por arquivo (especificação)

### 5.1 `engine/DeviceProfile.kt`

- Adicionar `screenPreviewSize(screenLongSide, heapMb)` (§4.2) e `detailCeiling(heapMb)`.
- Manter `tune()` como está (`DeviceProfile.kt:37-45`).
- Novas constantes: `BYTES_PER_PIXEL`, `DETAIL_MIN_PX`, faixas do L2.

### 5.2 `ui/LumenViewModel.kt`

- `open()`: decodificar `_original` com `screenPreviewSize(...)` em vez de `previewSize`
  (`LumenViewModel.kt:223`); manter `dragSource` (`:240-250`).
- Novo estado: `_detail: StateFlow<Bitmap?>`, `_detailReady: StateFlow<Boolean>`,
  `_detailRect` (recorte exibido / offset) para o Compose alinhar.
- Novo `fun requestDetail(viewport: RectF)` cancelável + cache + invalidação no `commit()`.
- `close()`: limpar `_detail`, caches e jobs de detalhe.
- `scheduleRender`: manter L0/L1; ao terminar L1, **não** invalidar L2 se o recorte não mudou
  (evitar re-render caro a cada settle).
- `commit()`: marcar detalhe como sujo (`detailDirty = true`).
- Settle por inatividade (§4.5): extrair para um `scheduleSettleAfterIdle()`.
- Remover a duplicação: `render` e `renderFast` são **idênticos**
  (`ImageEngine.kt:127-136`) — consolidar num só (`render`) enquanto se mexe aqui.

### 5.3 `engine/ImageEngine.kt`

- `decode(file, maxSize)` inalterado (`:95-108`).
- Novo `decodeRegion(file: File, rect: Rect, maxSize: Int?)`:
  - RAW → `RawDecoder.decode` + `Bitmap.createBitmap(src, x, y, w, h)`;
  - JPEG/PNG → `BitmapRegionDecoder` (com fallback para decode cheio + crop em caso de erro);
  - demais → decode cheio (cacheado) + crop.
- `render(src, p)`: já é único ponto de verdade; manter o `applyAll` (`:180-387`) como o
  "pipeline de pixel" e apenas acrescentar os blocos de detalhe (§6).
- `histogram`: manter lendo do preview de tela (barato; detalhe não deve alimentar histograma
  para não ficar incompleto).

### 5.4 `ui/EditorScreen.kt`

- `shown`: preferir L2 quando disponível e o zoom justificar; assinar `vm.detail`.
- Calcular o viewport visível e chamar `vm.requestDetail(...)` nos disparadores (§4.3).
- Botão "1:1" ao lado do indicador de zoom (`:365-380`) e selo de estado do detalhe.
- **Modo compara** (§7.2): divisão arrastável original|editado.
- Nova aba **Detalhe** (§7.3): atualizar `CATEGORIES` (`:101`), `MASK_TAB` (`:104`),
  `curvesTab = tab == 1` (`:432`) e o `when (tab)` (`:440-491`).
- Aba **Lente**: novos rótulos de aberração + deslocamento cromático (§7.4).

### 5.5 `model/EditParams.kt`

Novos campos (todos com default neutro, para compatibilidade):

```kotlin
val sharpness: Float = 0f,          // -1..1  (>0 nítido, <0 suave)         Efeitos/Detalhe
val sharpenRadius: Float = 0f,      //  0..1  -> raio derivado da resolução
val noiseLuminance: Float = 0f,     //  0..1
val noiseColor: Float = 0f,         //  0..1
val noiseDetail: Float = 0.5f,      //  0..1  (preservação de detalhe)
val chromaShiftRed: Float = 0f,     // -0.02..0.02  (franja uniforme R/C, fração da largura)
val chromaShiftBlue: Float = 0f,    // -0.02..0.02  (franja uniforme A/A)
```

- `toJson()`/`fromJson()` com `optDouble(..., default)` — JSON antigo continua válido
  (`model/EditParams.kt:33-88`).
- Presets novos (opcional): `NITIDO`, `RETRO_FRANJA` (`model/EditParams.kt:92-99`).

### 5.6 `ui/Components.kt`

- `AdjustSlider` (`:41-86`) já serve; se a nova aba crescer, extrair um `SectionHeader`.

---

## 6. Pipeline de imagem: nitidez e redução de ruído

### 6.1 Ordem correta

Ordem clássica de revelação: **reduzir ruído → ajustar tom → nitidez**. A nitidez por último
(não musturar ruído) e o NR antes de clareza/textura/nitidez (senão elas amplificam ruído).

No `applyAll` isso vira, dentro do bloco `useTone`:

```
1. luminância de trabalho
   lumaSrc   = luma(fonte)                          // já existe, :202-209
2. redução de ruído de LUMINÂNCIA
   lumaNr    = blur(lumaSrc, rLum)
   lumaWork  = mistura com peso de borda  (não suaviza arestas fortes)
3. NR de COR (croma)
   croma = (r,g,b) − lumaWork ;  cromaNr = blur(croma, rCor)
   (r,g,b) = lumaWork + mistura(croma, cromaNr, noiseColor)
4. bases para clareza / textura / nitidez, todas sobre lumaWork
   clarityBase = blur(lumaWork, max(4, max(w,h)/60))   // já existe, :210-212
   textureBase = blur(lumaWork, 2)                     // já existe, :213
   sharpBase   = blur(lumaWork, raioNitidez)           // NOVO
5. no laço de pixel (perto de :265-271):
   toned += sharpness * detalhe * ganho * (lumaWork[i] − sharpBase[i])
```

### 6.2 Matemática da nitidez (unsharp mask)

```
detalhe = lumaWork[i] − sharpBase[i]
ganho   = 1 + 2·sharpness              // sharpness ∈ [−1,1]
pw      = 1 − (2·clamp01(target) − 1)²  // peso que evita halo em branco/preto
toned  += sharpness · K · pw · ganho · detalhe        (K ≈ 0,5, calibrar)
```

- **Raio**: derivar da resolução para o efeito ser estável entre L0/L1/L2:
  `raioNitidez = max(1, round((0,7 + sharpenRadius) · fatorEscala))`, onde
  `fatorEscala = ladoMaiorRender / ladoMaiorOriginal`. Isso é essencial: 1 px de raio num
  preview de 1400 px é ~3× mais "grosso" que 1 px na nativa.
- **Negativo** (`sharpness < 0`) = suavização (blur leve), útil e simétrico com a UI.

### 6.3 Redução de ruído

**Luminância** (remove granulado preservando detalhe):

```
diff    = lumaWork[i] − lumaNr[i]
borda   = 1 − clamp01(|diff| / limiar)      // diff grande ⇒ aresta ⇒ não suaviza
peso    = noiseLuminance · borda · (0,35 + 0,65·noiseDetail)
lumaWork[i] = clamp01(lumaWork[i] − peso · diff)
```

**Cor** (remove manchas de cor sem tirar textura): suavizar a croma e devolver à luminância,
como no passo 3 acima. Isso é mais seguro que suavizar R/G/B direto (que escurece detalhe).

Observações:
- `noiseDetail = 0` ⇒ suavização agressiva; `1` ⇒ preserva tudo (quase sem efeito).
- Limiar de borda derivado de `noiseLuminance` (mais NR ⇒ tolera mais).
- Raio do croma maior que o da luminância (ruído de cor é mais espalhado).

### 6.4 Integração com o resto do motor

- **`isToneIdentity`** (`:548-552`): incluir detalhe **ou** criar `isDetailIdentity(p)` e somar
  em `isPixelIdentity` (`:138-143`) — sem isso, mexer só em nitidez não dispara render.
- **`lumaSrc`**: hoje só é alocado se `clarity != 0 || texture != 0` (`:202`); estender para
  `clarity || texture || sharpness || noiseLuminance || noiseColor`.
- **Custo**: cada blur são 2 passadas O(n) (`:573-597`). Contando os novos: nitidez (1) +
  NR lum (1) + NR cor (2 canais ≈ 2) → +4 blurs. Aceitável em L0/L1; em L2 é por recorte, e por
  isso o debounce e a guarda de memória são obrigatórios.
- **Máscaras**: ver §6.5.

### 6.5 Máscaras e detalhe (decisão de projeto)

`MaskAdjust` (`model/Masks.kt:67-109`) **não** ganhará nitidez/NR nesta fase, pelo mesmo motivo
já documentado para clareza/textura (`Masks.kt:65-66`): exigem vizinhança, e um blur mascarado
por pixel é caro. Se for desejado depois (Fase 3 opcional), a via é calcular o mapa de detalhe
**global** e aplicá-lo modulado pelo peso da máscara — nunca recalcular blur por máscara.

---

## 7. UI/UX

### 7.1 Ordem das abas

Inserir **"Detalhe"** entre "Efeitos" e "Lente" (mesma ordem do Lightroom: Light, Color,
Effects, **Detail**, Optics, Geometry):

```
0 Luz · 1 Curvas · 2 Cor · 3 Efeitos · 4 Detalhe · 5 Lente · 6 Geometria · 7 Máscara
```

Impactos obrigatórios (todos em `EditorScreen.kt`):
- `CATEGORIES` (`:101`) — acrescentar `"Detalhe"` após `"Efeitos"`.
- `MASK_TAB = 6` (`:104`) → **`7`**.
- `curvesTab = tab == 1` (`:432`) — não muda (Curvas continua 1).
- `when (tab)` (`:440-491`) — renumerar ramos 3→"Efeitos", **4→"Detalhe" (novo)**,
  5→"Lente", 6→"Geometria", 7→"Máscara".
- Default `tab` (`:125`) permanece 0.

### 7.2 Modo de comparação (antes/depois)

Duas entregas complementares:

- **Peek** (já existe): segurar mostra o original — manter, mas passar a usar o **mesmo nível**
  de L2 quando o detalhe estiver ativo (§4.3), para o "antes" ter o mesmo pixel do "depois".
- **Divisão** (novo): alternar no ícone Compare (`:160-173`) para um modo em que a foto é
  dividida em duas metades — esquerda original, direita editada — com uma **alça vertical
  arrastável**. Detalhes de implementação:
  - desenhar os dois `Bitmap` empilhados, recortando cada um com `clipRect` na fração;
  - a alça vive sobre a mesma transformação de zoom/pan, então ambas as metades ficam alinhadas;
  - rótulos "ANTES"/"DEPOIS" discretos nos cantos;
  - o gesto de arrastar a alça não deve conflitar com pan (usar alça dedicada, como a
    `detectDragGestures` da máscara em `:292-311`);
  - entrar no modo divisão **dispara** `requestDetail` (é o caso de uso principal).
- Ambos precisam de um "original" no nível L2. Duas opções:
  - (preferida) renderizar o original a partir do **mesmo recorte** com `EditParams()`
    (identity = cópia barata);
  - ou manter `detailBase` (decodificação nativa do recorte) como "antes".

### 7.3 Aba Detalhe (conteúdo)

| Controle | Faixa | Padrão | Display |
|---|---|---|---|
| Nitidez | −1..1 | 0 | `%+d` |
| Raio | 0..1 | 0 | `%.1f` |
| Redução de ruído — Luminância | 0..1 | 0 | `%d%%` |
| Redução de ruído — Cor | 0..1 | 0 | `%d%%` |
| Preservar detalhe | 0..1 | 0,5 | `%d%%` |

- Usar `Adjust`/`AdjustSlider` (`EditorScreen.kt:695-717`, `Components.kt:41-86`) para herdar
  reset por duplo-toque e o padrão verde do redesign.
- Duplo-toque já reseta (com exceção de `Raio`/`Preserve`, cujo default não é 0 — passar
  `default = 0.5f` como já se faz no slider de LUT, `EditorScreen.kt:668`).
- Dica contextual abaixo do grupo: "Amplie até 1:1 para julgar nitidez e ruído" — liga a aba
  Detalhe ao item 1 deste plano.

### 7.4 Aba Lente: aberração cromática add+remove

**Rótulos e direção** (reaproveitando os sliders atuais, `EditorScreen.kt:470-472`):

| Hoje | Proposto | Dica na tela |
|---|---|---|
| "Distorção" | "Distorção" | — |
| "Aberração Vermelho/Ciano" | **"Franja R/C"** (− reduz · + adiciona) | subtítulo de sinal |
| "Aberração Azul/Amarelo" | **"Franja A/A"** (− reduz · + adiciona) | subtítulo de sinal |
| — | **"Deslocar R/C"** (novo, não radial) | ±2 % da largura |
| — | **"Deslocar A/A"** (novo, não radial) | ±2 % da largura |

**Força criativa**: hoje `escala = 1 + v·0,004` (`ImageEngine.kt:507-508`) dá só ±0,4 %.
Para o lado "+" ser visível, trocar por uma curva com mais alcance na ponta e manter o lado
"−" fino (correção precisa onde importa):

```kotlin
fun chromaScale(v: Float): Float =          // v ∈ [-1, 1]
    if (v >= 0f) 1f + 0.012f * v * v         // + : cresce quadrático (até +1,2% = franja visível)
    else         1f + 0.004f * v             // − : linear e fino (correção)
```

> **Atenção à precisão da correção:** a curva acima mantém o lado negativo como está para não
> regredir quem já corrigiu franja. Calibrar com foto real de teste (grid de estrelas/linhas).

**Deslocamento uniforme** (a "adição" de verdade): na mesma passada de lente
(`applyLensCorrection`, `ImageEngine.kt:496-528`), somar um offset fixo em vez de escalar:

```
r = sampleChannel(pixels, w, h, cx + dx·f·redScale  + chromaShiftRed  * w, cy + dy·f·redScale,  16)
b = sampleChannel(pixels, w, h, cx + dx·f·blueScale + chromaShiftBlue * w, cy + dy·f·blueScale,  0)
```

Isso reproduz a franja lateral assimétrica típica de lentes antigas/retrô e é o que faltava.
`isLensIdentity` (`:493-494`) passa a incluir `chromaShiftRed/Blue`.

**Guarda de borda**: `sampleChannel` já clampa (`:530-546`) — com shift grande, aparecem
listras de borda. Documentar e manter o teto de ±2 % (≈ ±21 px em 1080) para não estourar.

**Presets** (opcional, `model/EditParams.kt:92-99`): `RETRO_FRANJA`
(ex.: `chromaShiftRed = 0.006f`, `chromaShiftBlue = -0.006f`, `sharpness = -0.1f`, `vignette = 0.25f`).

---

## 8. Compatibilidade, persistência e migração

- **JSON**: campos novos lidos com default (`EditParams.fromJson`, `model/EditParams.kt:60-88`).
  Fotos antigas abrem sem alteração; fotos novas em app antigo simplesmente ignoram os campos
  (o app antigo não reconhece `sharpness` etc., sem quebrar).
- **`versionCode`/`versionName`** vêm da tag (`app/build.gradle.kts:18-19`) — nada a mudar.
- **Exportação** (`LumenViewModel.kt:384-398`) já usa nativa e passa a incluir os novos efeitos
  automaticamente, desde que `render` chame o `applyAll` estendido.
- **Máscaras**: nenhuma mudança de geometria; o overlay (`EditorScreen.kt:204-216`) segue igual.
- **Estabilidade numérica**: usar `clamp01` (`ImageEngine.kt:599`) em todos os novos passes.

---

## 9. Testes e verificação

### 9.1 Testes

- **Instrumentados** (`app/src/androidTest/java/com/jabs/lumen/`, infra já existe com
  `lenna.jpg`):
  - `EditParams` round-trip JSON com os campos novos (o `org.json` funciona em androidTest).
  - `ImageEngine`: com `sharpness = 0` e NR `= 0`, a saída é **idêntica** byte a byte à de hoje
    (garantia de não-regressão).
  - `sharpness > 0` aumenta a variância local (medida em janela) e não estoura 0..255.
  - `noiseColor > 0` reduz a diferença entre canais em região de ruído sintético.
  - `chromaShiftRed > 0` desloca o canal R (correlação cruzada na horizontal) e o sinal
    negativo o desloca ao contrário.
  - `DeviceProfile.screenPreviewSize` respeita o orçamento de memória (nunca acima do teto).
- **Manual/de dispositivo**: ver §9.3.

### 9.2 Build

```bash
# emulador x86_64 (o release sai só arm64-v8a)
./gradlew :app:assembleDebug -PlumenAbis=arm64-v8a,x86_64
./gradlew :app:connectedDebugAndroidTest -PlumenAbis=x86_64
```

### 9.3 Roteiro de verificação em tela (capturas para `docs/ui-depois/`)

1. Abrir uma foto de 12 MP. Sem tocar em nada: confirmar que o preview parado está no nível
   **tela** (nitidez nos detalhes finos no fit).
2. Pinça até 8×: confirmar que aparece o selo "detalhe 1:1" e que o texto/folhagem mostra
   **pixel real**, não borrão.
3. Segurar Compare e entrar em **divisão**: confirmar antes/depois em 1:1, com a alça alinhada.
4. Aba **Detalhe**: subir Nitidez e ver o efeito **só** quando estiver ampliado; medir o tempo
   de render e confirmar que a UI não trava.
5. Aba **Mistura** (Cor): arrastar um slider e soltar. Confirmar que, após ~180 ms, a imagem
   volta ao nível tela **sem** apertar "Confirmar" (regressão do defeito §2.2b).
6. Aba **Lente**: "Franja R/C" em −30 (reduz) e em +80 (adiciona) — confirmar que os dois
   sentidos são visíveis e distintos; idem "Deslocar R/C".
7. Exportar e comparar o JPEG em `Pictures/Lumen` com o que era mostrado em 1:1.
8. Foto grande (48 MP): confirmar que não há OOM e que o teto de memória entra em ação.

---

## 10. Riscos e mitigação

| Risco | Impacto | Mitigação |
|---|---|---|
| OOM ao renderizar nativa | Crash | L2 por **recorte**, teto por orçamento de heap, `try/catch` com fallback para L1 |
| Zoom/jank durante a pinça | Sensação de lentidão | L2 com **debounce** e cancelamento, nunca durante o gesto |
| Desalinhamento do recorte (zoom/pan) | Imagem "pulando" | Reaproveitar a mesma convenção de `toImageCoords`/`clampOffset`; teste visual dedicado |
| Nitidez acentuando ruído | Resultado sujo | ordem NR→nitidez e peso por luminância; raio derivado da resolução |
| NR "plastificando" | Perda de textura | `noiseDetail` obrigatório e padrão 0,5 |
| Correção de franja regredir | Fotos já corrigidas pioram | manter o lado negativo com o fator atual (0,004) |
| Custos de memória do pool | Complexidade | pool só para os arrays maiores; medir antes/depois |
| Detalhe desatualizado após editar | "Antes/depois" mentiroso | `detailDirty` no `commit()`; recomputar ao reexibir 1:1 |

---

## 11. Faseamento sugerido

| Fase | Escopo | Valor entregue |
|---|---|---|
| **F1** | Settle universal (§4.5) + teto de L1 por resolução de tela (§4.2) | Corrige "fica em baixa o tempo todo" no caso comum; fit fica fiel |
| **F2** | Aba **Detalhe** com Nitidez + NR (§6, §7.3) | Atende o pedido 2 |
| **F3** | L2 **detalhe 1:1** por viewport + botão 1:1 + selo (§4.3) | Atende o pedido 1 de verdade |
| **F4** | **Comparação em divisão** (§7.2) | "Como vou comparar antes/depois?" resolvido |
| **F5** | Franja cromática add+remove + deslocamento + presets (§7.4) | Atende o pedido 3 |
| **F6** | (opcional) Detalhe em máscaras; otimizações de memória/GPU | Refinamento |

> Sugestão de ordem de implementação dentro de cada fase: modelo → motor → testes
> instrumentados → UI. Assim cada fase fecha com o motor verificável antes da tela.

---

## 12. Checklist de aceite

- [ ] Existe um nível de prévia que mostra **pixels nativos** (1:1) do arquivo, sob demanda.
- [ ] Após qualquer ajuste (inclusive Mistura e Gradação), a imagem volta ao nível de tela
      sozinha, sem botão "Confirmar".
- [ ] Comparação antes/depois disponível em **1:1** (peek **e** divisão arrastável).
- [ ] Aba **Detalhe** com Nitidez, Raio, NR Luminância, NR Cor e Preservar detalhe.
- [ ] `sharpness=0, noise*=0` ⇒ saída idêntica à atual (sem regressão de imagem).
- [ ] Franja cromática permite **reduzir e adicionar**, com rótulos de direção explícitos.
- [ ] Deslocamento cromático não radial disponível.
- [ ] JSON antigo carrega; JSON novo exporta; exportação aplica tudo.
- [ ] Sem OOM em foto de 48 MP; cancelamento e debounce no zoom.
- [ ] `CATEGORIES`, `MASK_TAB`, `curvesTab` e o `when(tab)` consistentes após a nova aba.
- [ ] Capturas novas em `docs/ui-depois/` e README atualizado (seção de interface/ajustes).

---

### Anexo A — mapa rápido de pontos de mudança

| Arquivo | O que muda |
|---|---|
| `engine/DeviceProfile.kt` | `screenPreviewSize`, `detailCeiling`, orçamento de memória |
| `engine/ImageEngine.kt` | `decodeRegion`, nitidez/NR em `applyAll`, franja uniforme em `applyLensCorrection`, novas identidades, consolidação `render`/`renderFast` |
| `model/EditParams.kt` | 7 campos novos + JSON + presets |
| `ui/LumenViewModel.kt` | `_detail`, `requestDetail`, settle por inatividade, limpeza no `close` |
| `ui/EditorScreen.kt` | seleção de nível, botão 1:1, modo divisão, aba Detalhe, rótulos na Lente, índices de aba |
| `ui/ColorMixerPanel.kt` / `ui/ColorGradingPanel.kt` | `onCommit` ao soltar o dedo |
| `ui/Components.kt` | (opcional) `SectionHeader` |
| `AndroidManifest.xml` | (avaliar) `largeHeap` só como folga |

### Anexo B — glossário de parâmetros novos

| Campo | Faixa | Padrão | Significado |
|---|---|---|---|
| `sharpness` | −1..1 | 0 | nitidez (+) / suavização (−) |
| `sharpenRadius` | 0..1 | 0 | raio do unsharp mask (deriva da resolução) |
| `noiseLuminance` | 0..1 | 0 | redução de ruído de luminância |
| `noiseColor` | 0..1 | 0 | redução de ruído de cor (croma) |
| `noiseDetail` | 0..1 | 0,5 | quanto preservar de detalhe no NR |
| `chromaShiftRed` | −0,02..0,02 | 0 | franja uniforme vermelho/ciano (fração da largura) |
| `chromaShiftBlue` | −0,02..0,02 | 0 | franja uniforme azul/amarelo (fração da largura) |
