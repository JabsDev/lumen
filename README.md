# Lumen

Editor de fotos para Android, no espírito do Lightroom: revelação (luz, cor, curvas), LUTs,
máscaras (linear, radial, pincel, céu, objeto, pessoa) e controle remoto de câmera Lumix.

## Build

Requer o JDK 21 e o Android SDK (o caminho fica em `local.properties`, que não vai pro repo).

```bash
JAVA_HOME=/caminho/para/jdk-21 ./gradlew :app:assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

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
