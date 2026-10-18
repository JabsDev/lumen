# Lumen

Editor de fotos para Android, no espírito do Lightroom: revelação (luz, cor, curvas), LUTs,
máscaras (linear, radial, pincel, céu, objeto, pessoa) e controle remoto de câmera Lumix.

## Interface

As abas de ajuste ficam **na parte de baixo**, como no Lightroom: tocar numa aba abre o painel de
sliders logo acima dela, e **tocar na mesma aba de novo fecha o painel**, deixando a foto com a tela
inteira. As abas continuam ali embaixo e nenhuma fica acesa enquanto o painel está fechado. As abas
são só texto, sem contorno; a ativa fica em verde.

O fundo é cinza-neutro (para não contaminar a leitura de cor ao editar) e o **verde-menta** marca
ação e seleção: aba ativa, sliders, botões de confirmação, checks e o FAB de importar.

As **predefinições** (Natural, Vívido, Quente, Frio, Filme…) vivem dentro da aba **Luz**, no topo do
painel: são o ponto de partida do ajuste, e por isso não ocupam uma faixa permanente na tela.

O histograma — a silhueta de luminância com os três canais RGB traçados por cima — fica no canto
superior direito da **foto**, não numa faixa fixa: assim ele acompanha o quadro da imagem mesmo
quando ela não preenche a área disponível. Um toque nele esconde os canais de cor e deixa só a
luminância.

## Build

Requer o JDK 21 e o Android SDK (o caminho fica em `local.properties`, que não vai pro repo).

```bash
JAVA_HOME=/caminho/para/jdk-21 ./gradlew :app:assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

O APK sai só com `arm64-v8a`. Para rodar num emulador x86_64, peça a ABI:

```bash
./gradlew :app:assembleDebug -PlumenAbis=arm64-v8a,x86_64
```

## Máscaras

Cada máscara é um peso por pixel (0 = sem efeito, 1 = efeito total) calculado no
`MaskEngine`, e todas podem ser invertidas e ajustadas com Difusão e Opacidade.

| Chip | Como funciona |
| --- | --- |
| Linear / Radial | Gradiente analítico, sem modelo. |
| Pincel | Traços com tamanho, apagar/refazer. |
| Céu | Heurística de cor (azul), brilho e suavidade, presa à metade de cima. |
| **Objeto** / **Fundo** | Segmentação por toque: um modelo TFLite offline devolve **o objeto sob o dedo**, seja ele qual for. Toques seguintes somam outros objetos; no modo Subtrair, eles saem. É o "Select Objects" do Lightroom. |
| **Pessoa** | Modelo TFLite offline de pessoa. |

Os dois modelos rodam no aparelho, sem rede, e ficam nos `assets` (descomprimidos no APK para
serem mapeados em memória):

- `magic_touch.tflite` (~6 MB, MediaPipe Interactive Segmenter / MagicTouch, Apache-2.0) — objeto;
- `subject.tflite` (~250 KB, MediaPipe Selfie Segmenter, Apache-2.0) — pessoa.

Se um modelo não carrega, o chip cai para uma seleção por cor (crescimento em Lab a partir do
toque), então nunca fica inerte.

### Conferindo os modelos

Os dois têm teste instrumentado que roda no aparelho ou emulador e confere contra os números do
**MediaPipe oficial** (não contra uma reimplementação nossa):

```bash
./gradlew :app:connectedDebugAndroidTest -PlumenAbis=x86_64
```

O pré-processamento do modelo de objeto é fácil de errar e está explicado no cabeçalho do
`ObjectSegmenter.kt` — inclusive o que **não** funcionou.

## Atualizações automáticas

O app se atualiza a partir dos **Releases** deste repositório:

1. O updater consulta `releases/latest` na API pública do GitHub.
2. Se a tag for mais nova que a versão instalada, ele baixa o `.apk` e abre o instalador do
   Android (é preciso permitir "instalar apps desconhecidos" uma vez).

### Publicando uma versão

O versionamento sai da tag do git (`v1.2.3` → `versionName 1.2.3`, `versionCode 10203`):

```bash
git tag v0.2.0
git push origin v0.2.0
```

O workflow `.github/workflows/release.yml` então compila o APK assinado e cria o Release com o
arquivo anexado. O app passa a oferecer a atualização sozinho.

> Por isso o repositório é **público**: o updater baixa o APK sem token. O keystore de assinatura
> fica num *secret* do repositório (`DEBUG_KEYSTORE_BASE64`), nunca no código.
