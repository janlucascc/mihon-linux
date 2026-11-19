<div align="center">

# ⛩️ Mihon Linux

**Leitor de Mangá & Manhwa minimalista, nativo e de alta performance para Linux**

[![Versão](https://img.shields.io/badge/versão-0.4.0-crimson.svg?style=flat-square)](https://github.com/janlucascc/mihon-linux/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21+-ED8B00.svg?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![Linux](https://img.shields.io/badge/Plataforma-Linux-FCC624.svg?style=flat-square&logo=linux&logoColor=black)](https://kernel.org)
[![Licença](https://img.shields.io/badge/licença-MIT-blue.svg?style=flat-square)](LICENSE)

</div>

---

## ✨ Destaques

- **📖 Leitor Completo**: Modos *Single*, *Double RTL* (oriental) e *Webtoon* contínuo. Scrubber slider de precisão, fita de marcadores/notas e drawer lateral rápido de capítulos.
- **🌐 Tradução Simultânea (OCR / Google Lens)**: Selecione qualquer balão de diálogo diretamente no leitor e visualize a tradução sobreposta em tempo real.
- **🫧 Menu Radial Gooey**: Ações de contexto fluidas com metaballs vetoriais analíticas em tempo real, sem travamentos e com zero consumo de GPU/CPU.
- **🥷 Modo Anônimo**: Alterne com um clique na barra superior; badge visual animado protege seu histórico e progresso de leitura.
- **📊 Estatísticas & Wrapped**: Acompanhe o tempo de leitura, mangás mais lidos, capítulos concluídos e hábitos semanais.
- **⚡ Desempenho & RAM Otimizados**: Motor `MemoryManager` com controle rígido de bytes, leitura em streaming de arquivos `.cbz`, descarte proativo de páginas fora da tela e limpeza de cache.
- **🈯 Tipografia CJK Integrada**: Fallback automático para fontes em Japonês, Coreano e Chinês, eliminando caracteres ausentes (*tofu*).
- **📥 Catálogo & Modo Offline**: Pesquisa unificada em fontes (MangaDex, Manga Livre e extensões customizadas) com downloads atômicos em `.cbz` para ler sem internet.
- **🐧 Integração com Desktop Linux**: Suporte nativo a temas escuros (FlatLaf), notificações do sistema e ícone na bandeja (*System Tray*).

---

## ⌨️ Atalhos Principais

| Atalho | Ação |
| :--- | :--- |
| `→` / `PgDn` / `Espaço` | Próxima página / Rolar para baixo |
| `←` / `PgUp` | Página anterior / Rolar para cima |
| `[` / `]` | Capítulo anterior / Próximo capítulo |
| `Ctrl + Scroll` | Zoom livre contínuo |
| `+` / `-` / `0` | Ajustar / Resetar zoom |
| `T` | Ativar modo tradução (OCR / Seleção) |
| `B` | Alternar marcador no capítulo |
| `H` / `M` | Ocultar / Exibir barras do leitor (HUD) |
| `F11` | Alternar tela cheia |
| `Esc` | Sair da tela cheia ou fechar leitor |

---

## 🚀 Instalação e Execução

### Pré-requisitos
- **Linux** (X11 ou Wayland via XWayland)
- **Java JDK 21+** (`openjdk-21-jdk` ou equivalente)

### Compilar e Rodar

```bash
# 1. Clonar o repositório
git clone https://github.com/janlucascc/mihon-linux.git
cd mihon-linux

# 2. Executar diretamente
./gradlew :desktop:run

# (Opcional) Criar pacote/binário local
./gradlew :desktop:installDist -x test
./desktop/build/install/desktop/bin/desktop
```

---

## 🏗️ Estrutura do Projeto

```text
mihon-linux/
├── core/archive/     # Parsers de .cbz, .zip e streaming de imagens
├── domain/           # Modelos de domínio (Manga, Chapter, Category, History)
├── source-api/       # Contratos de extensões e scrapers online
├── data/             # Banco SQLite, migrações e persistência de dados
└── desktop/          # UI Swing/FlatLaf, Leitor, Tradutor, OCR e MemoryManager
```

---

## 📄 Licença

Distribuído sob a licença **MIT**. Consulte [`LICENSE`](LICENSE) para mais informações.
