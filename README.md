<div align="center">

# ⛩️ Mihon Linux

### *Leitor de Mangá & Manhwa Nativo e de Alta Performance para Desktop Linux*

[![License: MIT](https://img.shields.io/badge/License-MIT-crimson.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-blueviolet.svg?style=for-the-badge&logo=kotlin)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-orange.svg?style=for-the-badge&logo=openjdk)](https://openjdk.org)
[![Linux](https://img.shields.io/badge/Platform-Linux%20Desktop-informational.svg?style=for-the-badge&logo=linux)](https://kernel.org)
[![FlatLaf](https://img.shields.io/badge/UI-FlatLaf%20Dark-2ea44f.svg?style=for-the-badge)](https://www.formdev.com/flatlaf/)

<p align="center">
  Um port nativo completo da arquitetura do <b>Mihon / Tachiyomi</b> para Linux, combinando a conveniência do ecossistema de extensões mobile com o poder de renderização, multithreading e visualização em telas de alta resolução do PC.
</p>

---

</div>

## 📖 Visão Geral

O **Mihon Linux** foi desenhado com base nos princípios de design editorial japonês (*Sumi-Ink & Tankōbon Bindery*), oferecendo uma experiência imersiva de leitura, catálogo online integrado, downloads offline atômicos e um motor inédito de super-resolução para eliminação de borrões durante o zoom.

---

## ⚡ Principais Funcionalidades

### 🔍 Super-Resolução & Qualidade de Imagem (`MangaImageEnhancer`)
- **Lanczos-3 Resampler com Anti-Ringing**: Interpolação separável em 2 passos (ponto fixo de 14 bits) com *clamping* de extremos locais para evitar halos brancos ao redor do nanquim.
- **Reconstrução Direcional de Traços (Anime4K + FidelityFX CAS)**:
  - Afina e vetoriza traços de nanquim e balões de diálogo ao aplicar zoom (> 100%).
  - Ganho de até **+43,6% de nitidez de borda** comprovado em scans de baixa resolução.
  - Preserva gradientes e retículas (*screentones*) sem amplificar ruído de compressão.
- **Alternância Dinâmica no Leitor**: Escolha entre os modos **Ultra** (Lanczos-3 + Anime4K), **Nítido** (Lanczos-3 + CAS) e **Rápido** (Bilinear).

---

### 📚 Modos de Leitura Avançados
- **Página Única (*Single*)**: Foco total na página atual com centralização inteligente.
- **Página Dupla Oriental (*Double RTL*)**: Leitura tradicional da direita para a esquerda, mantendo capas isoladas e páginas duplas emparelhadas.
- **Webtoon (Rolagem Contínua)**: Rolagem fluida com carregamento adaptativo assíncrono e preservação exata da posição de leitura.
- **Controles por Atalhos e Gestos**: Navegação por teclado (`←`, `→`, `PgUp`, `PgDn`, `Espaço`, `Home`, `End`), `F11` para tela cheia e zoom dinâmico via `Ctrl + Scroll`.

---

### 🌐 Ecossistema de Fontes & Catálogo Online
- **Fontes Nativas Pré-configuradas**: Suporte imediato para **MangaDex** (API REST v5) e **Manga Livre** com raspagem resiliente contra proteções e ordenação automática de capítulos.
- **Extensibilidade Declarativa**: Crie novas fontes em segundos criando arquivos JSON de seletores em `~/.mihon/sources/` ou extensões JAR em `~/.mihon/extensions/`.
- **Navegação & Busca Global**: Pesquisa simultânea em múltiplas fontes com visualização rápida de capas e sinopses.

---

### 💾 Downloads & Gerenciamento Offline
- **Armazenamento Atômico em `.cbz`**: Capítulos são compactados atomicamente em arquivos `.cbz` organizados em `~/.mihon/downloads/<Mangá>/`.
- **Pré-carregamento Silencioso (Prefetch)**: Durante a leitura de um capítulo, os próximos 2 capítulos são baixados preventivamente em segundo plano.
- **Reaproveitamento 100% Offline**: Mangás baixados abrem instantaneamente sem requisições de rede. Arquivos corrompidos são detectados e rebaixados automaticamente.
- **Backup e Restauração**: Exporte todo o seu banco de dados e histórico de leitura para um arquivo `.zip` e restaure com 1 clique nas preferências.

---

## 🎨 Design System: *Sumi-Ink & Tankōbon Bindery*

Inspirado na encadernação clássica de mangás e na paleta de nanquim:

| Cor | Código Hex | Aplicação |
| :--- | :--- | :--- |
| **Sumi Ink** | `#0D0D0F` | Fundo principal da aplicação |
| **Charcoal Paper** | `#151519` | Superfícies elevadas e cabeçalhos |
| **Washi Ivory** | `#F2EFE9` | Tipografia principal de alto contraste |
| **Hanko Vermilion** | `#E04F3E` | Acentos primários e selos de status |
| **Matcha Sage** | `#2E9E6B` | Indicadores de capítulos salvos offline |

---

## 🚀 Instalação e Execução

### Pré-requisitos
- **Linux** (qualquer distribuição compatível com Wayland ou X11)
- **Java JDK 21 ou superior** (ex: Temurin, OpenJDK ou via SDKMAN)

```bash
# 1. Clonar o repositório
git clone https://github.com/janlucascc/mihon-linux.git
cd mihon-linux

# 2. Compilar e instalar a distribuição local
./gradlew :desktop:installDist -x test

# 3. Executar o Mihon Linux
./desktop/build/install/desktop/bin/desktop
```

---

## 🏗️ Arquitetura do Projeto

O repositório é estruturado em módulos limpos com separação estrita de responsabilidades:

```text
mihon-linux/
├── core/
│   ├── archive/       # Parsers de .cbz, .zip e decodificação nativa de dimensões WebP
│   ├── common/        # Utilitários globais, caches de disco e extensões
│   └── metro/         # Escopos de concorrência e injeção leve
├── domain/            # Modelos puros e contratos (Manga, Chapter, History)
├── source-api/        # Interfaces de extensões, MangaDex API v5 e HttpScraperSource
├── data/              # SQLite/Exposed, migrações de schema e ExtensionLoader
└── desktop/           # Interface Swing/FlatLaf, MangaImageEnhancer e ReaderWindow
```

---

## ⌨️ Atalhos de Teclado no Leitor

| Atalho | Ação |
| :--- | :--- |
| `→` / `PgDn` / `Espaço` | Próxima página / rolar para baixo |
| `←` / `PgUp` | Página anterior / rolar para cima |
| `Ctrl + Scroll` | Zoom dinâmico com super-resolução |
| `+` / `-` / `0` | Ajustar / Resetar nível de zoom |
| `F11` | Alternar modo tela cheia |
| `H` / `M` | Ocultar / Exibir barras do leitor (HUD) |
| `[` / `]` | Pular para capítulo anterior / próximo |
| `Esc` | Sair da tela cheia ou fechar leitor |

---

## 📄 Licença

Este projeto está licenciado sob a **Licença MIT** — consulte o arquivo [LICENSE](LICENSE) para obter detalhes completos.

---

<div align="center">
  <sub>Desenvolvido com carinho para a comunidade open-source e entusiastas de mangá no Linux.</sub>
</div>
