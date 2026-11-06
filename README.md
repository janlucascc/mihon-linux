# Mihon Linux

Port nativo para desktop Linux do leitor de mangás **Mihon / Tachiyomi**, desenvolvido com **Kotlin**, **Swing / FlatLaf**, **SQLite / Exposed** e **OkHttp / jsoup**.

![Mihon Linux](https://raw.githubusercontent.com/janlucascc/mihon-linux/main/docs/preview.png) *(opcional)*

## ✨ Funcionalidades

- **Biblioteca e Categorias**: Organização de acervo com abas de categorias (*Todos*, *Lendo*, *Completos*, *Quero ler*), contadores de capítulos não lidos e filtros em grade confortável ou compacta.
- **Leitor Avançado Multimodo**:
  - Página única (*Single*)
  - Página dupla com leitura oriental Direita → Esquerda (*Double RTL*)
  - Rolagem contínua para Webtoon
  - Ajustes de zoom, tela cheia (*F11*) e navegação por teclado / mouse.
- **Super-Resolução & Qualidade de Imagem**:
  - Motor **Lanczos-3** com clamping *Anti-Ringing* integrado.
  - Reconstrução de traços finos e *Contrast-Adaptive Sharpening* (**Anime4K / FidelityFX CAS**) para manter nanquim e balões de texto nítidos em monitores de alta resolução e durante zoom.
- **Fontes Online & Catálogo**:
  - Extensões nativas pré-configuradas (*MangaDex*, *Manga Livre*).
  - Suporte a fontes customizadas via JSON declarativo (`~/.mihon/sources/`) e extensões JAR (`~/.mihon/extensions/`).
- **Download Offline Completo**:
  - Download atômico de capítulos diretamente para arquivos `.cbz`.
  - Pré-carregamento automático em background dos próximos capítulos durante a leitura.
  - Reutilização 100% offline de dados já baixados sem chamadas desnecessárias de rede.
- **Design Editorial Moderno**:
  - Estética *Sumi-Ink & Tankōbon Bindery* com tema escuro imersivo, tipografia serifada editorial e indicadores artesanais (*Hanko Vermilion*).
- **Backup e Restauração**:
  - Exportação e importação completa da biblioteca e histórico em arquivo `.zip` através das Preferências.

## 🛠️ Requisitos e Compilação

- **Java JDK 21+**
- **Linux** (Fedora, Ubuntu, Arch, etc.)

Para compilar e executar o projeto:

```bash
# Compilar e instalar a distribuição
./gradlew :desktop:installDist -x test

# Executar o leitor
./desktop/build/install/desktop/bin/desktop
```

## 📁 Estrutura do Projeto

- `:core:archive`: Leitores de arquivos `.cbz`, `.zip`, decodificação de imagens e detecção de dimensões WebP.
- `:core:common`: Utilitários compartilhados e extensões.
- `:source-api`: Modelos e interfaces de fontes e scrapers HTTP.
- `:domain`: Entidades de domínio e casos de uso portados do ecossistema Tachiyomi/Mihon.
- `:data`: Implementação de banco de dados SQLite/Exposed, repositórios e carregador de extensões.
- `:desktop`: Interface gráfica em Swing/FlatLaf, leitor, biblioteca, catálogo e super-resolução.

## 📄 Licença

Distribuído sob a licença open-source correspondente ao ecossistema Tachiyomi/Mihon.
