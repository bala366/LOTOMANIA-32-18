# LOTOMANIA V5 - MÉTRICA DOS 20

Versão independente da V4. O motor estatístico foi mantido; as alterações são de interface e PDF.

## Fluxo no aplicativo
1. Carregar concursos TXT/CSV
2. Gerar jogo
3. Gerar PDF do volante

O PDF mostra 00–99 em grade: 50 dezenas escolhidas em verde e 50 falhas em branco.

## Build
GitHub Actions: `.github/workflows/build-apk.yml`
Java 17 + Android SDK 35 + Gradle 8.9.
