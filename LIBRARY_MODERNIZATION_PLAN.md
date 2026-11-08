# Plano Definitivo de Modernização e Arquitetura Comparativa da Aba Biblioteca (Referência Visual Libib)

> **Documento de Arquitetura Técnica — Milestone M1**  
> **Status:** Aprovado para Implementação  
> **Data:** 2026-11-08  
> **Referência Visual Principal:** Foto 1 (Libib Layout Geral & Modo Grade Compacta: `media_1794097293781_385dbe79.jpg`, `media_1794097903700_99826d00.png`)  
> **Referência Upstream:** Mihon Android (`https://github.com/mihonapp/mihon`)  
> **Código-Alvo:** `/home/janlucas/mihon-linux` (`desktop/src/main/kotlin/mihon/desktop/LibraryScreen.kt`, `MangaPosterCard.kt`)  
> **Restrição de Integridade M1:** O código-fonte de `LibraryScreen.kt` e de todos os arquivos de código em `mihon-linux` permanece 100% inalterado nesta fase de planejamento.

---

## 1. Sumário Executivo e Objetivos da Modernização

A aba **Biblioteca** é o núcleo funcional e visual primário do Mihon Linux Desktop. Atualmente, a tela (`LibraryScreen.kt`) implementa uma adaptação simplificada do padrão mobile do Android, resultando em:
1. Subaproveitamento do espaço horizontal típico de monitores desktop (1080p, 1440p, 4K);
2. Campo de busca oculto por padrão (exigindo clique manual prévio);
3. Ausência de indicadores visuais de densidade de acervo (como contadores em pílula na barra de título);
4. Controles de ordenação e filtro confinados a um menu pop-up colapsado sem suporte à inversão de sentido (ascendente/descendente);
5. Ausência de barra de salto alfabético (obrigando o usuário a rolar exaustivamente uma lista longa);
6. Inicialização de fábrica no modo confortável (`compactMode = false`), gerando grandes espaços vazios sob cada capa.

O objetivo deste projeto de modernização é transformar a aba Biblioteca do Mihon Linux em uma experiência de classe mundial para desktop, inspirada diretamente na ergonomia, elegância e densidade de informação do **Libib** (Foto 1), combinando a fidelidade dos recursos de catálogo do **Mihon Android upstream** com o poder do Java Swing e FlatLaf.

### Requisito Mandatório de Fábrica
Conforme determinado nos requisitos contratuais do projeto e validado na Foto 1 (`media_1794097903700_99826d00.png`):
> **O modo de exibição "Grade compacta (título sobre capa)" (`LibraryDisplayMode.COMPACT_GRID`) é definido de forma explícita e obrigatória como o PADRÃO DE FÁBRICA inicial da aplicação.**

---

## 2. Análise Comparativa Aprofundada: `LibraryScreen.kt` Atual vs. Foto 1 (Libib)

### 2.1 Matriz Comparativa Estruturada

| Dimensão de Análise | Implementação Atual (`LibraryScreen.kt`) | Referência Visual Libib (Foto 1) | Mihon Android Upstream | Diretriz da Modernização no Mihon Linux |
| :--- | :--- | :--- | :--- | :--- |
| **Padrão de Exibição Inicial** | `compactMode = false` (Linha 59): inicia na Grade Confortável com textos em caixa de 74px abaixo da capa | Modo compacto com títulos integrados à capa (Foto 1B: `✓ Grade compacta (título sobre capa)`) | `val default = CompactGrid` (`LibraryDisplayMode.kt:24`) | **MANDATÓRIO: `LibraryDisplayMode.COMPACT_GRID` como padrão oficial de fábrica** |
| **Sistema de Busca** | Campo de texto oculto (`isVisible = false`, linha 72), requer clique no ícone de lupa para expandir | Campo de busca rápido, central e **persistente** em formato de pílula retangular arredondada com lupa | Busca rápida em App Bar com expansão suave | **`FlatSearchField` persistente na Top Bar** com ícone embutido, botão de limpeza e atalho `Ctrl+F` |
| **Identificação da Coleção** | `JLabel("Biblioteca")` estático de 22px sem contadores (linhas 222-225) | Título da coleção (`My Books`), **Pill Badge** com quantidade de obras `[ 112 ]` e chevron dropdown `>` | `LibraryToolbar` com pílula `Pill("${title.numberOfManga}")` | **Sub-Header dinâmico**: título da categoria ativa + badge pill `[ 112 ]` (ou `[ 14 / 112 ]`) + chevron |
| **Controles de Exibição** | Item único de checkbox escondido em `JPopupMenu` de filtros (linhas 174-181) | Botão seletor dedicado na toolbar: `[ ▦ Cover ]` com ícone e rótulo | Menu suspenso de visualização com 4 modos | **Botão seletor dedicado `[ ▦ Grade compacta ▼ ]`** abrindo pop-up com 4 modos oficiais |
| **Controles de Ordenação** | 3 opções fixas em radio button (`TITLE`, `UNREAD`, `ADDED`) sem inversão (linhas 48-52, 193-202) | Botão seletor `[ ≡ Title ]` acompanhado de botão de alternância de direção `[ ↓ ]` | Critérios múltiplos com alternância ascendente/descendente | **Botão duplo de ordenação**: seletor de critério `[ ≡ Título ]` + botão de sentido `[ ↓ ]` / `[ ↑ ]` |
| **Controles de Filtro** | Ícone simples de filtro que abre o menu genérico (linhas 165-205) | Botão `[ ☵ Filters ]` com destaque visual de cor de acento (cyan/teal) | Sheet de filtros com contagem e destaque | **Botão destacado `[ ☵ Filtros ]`** com preenchimento em `ACCENT_SOFT` e badge de filtros ativos `[ ☵ Filtros (2) ]` |
| **Navegação Alfabética** | **Inexistente**. Usuário precisa rolar toda a lista manualmente com roda do mouse | **Alphabet Jump Bar horizontal completa**: `A B C D E F G H I J K L M N O P Q R S T U V W X Y Z # ALL` | Fast scroller lateral | **Barra de salto alfabético `LibraryAlphabetBar`** com scroll suave instantâneo para a letra |
| **Renderização de Posters** | `CARD_H = COVER_H + 74 = 326px` (linhas 13-14 de `MangaPosterCard.kt`). Gradiente simples de 74px | Proporção física de livro (~2:3), sombra suave de elevação, marcadores de fita/ribbon | Gradiente de 33% (`fillMaxHeight(0.33f)`) e sombra de texto preta | **Proporção otimizada**: `176x252 px`, gradiente escuro de 78px com dupla sombra de texto |

---

### 2.2 Barra Superior e Sistema de Busca

#### Estado Atual (`LibraryScreen.kt:70-73`, `153-163`, `227-240`)
No código atual, o campo de busca `filterField` é declarado como:
```kotlin
private val filterField = JTextField(20).apply {
    putClientProperty("JTextField.placeholderText", "Pesquisar na biblioteca…")
    isVisible = false
}
```
O campo fica invisível na barra de ações. Ao clicar no botão `btnSearch`, a visibilidade é alternada (`filterField.isVisible = !filterField.isVisible`). Isso cria fricção cognitiva e atraso para o usuário desktop, que está acostumado a atalhos rápidos ou busca imediata.

#### Solução Baseada na Referência Libib (Foto 1)
No Libib, a busca é central, imediatamente acessível e estilizada:
1. **Permanência**: O campo nunca é ocultado. Fica centralizado na Top Bar com largura flexível/responsiva (mínimo 320px, preferencial 420px, máximo 560px).
2. **Ícone Integrado**: Lupa moderna FlatLaf à esquerda (`putClientProperty("JTextField.leadingIcon", ModernIcons.search(16))`).
3. **Botão de Limpeza Integrado**: Botão trailing `✖` visível apenas quando o campo contiver texto (`putClientProperty("JTextField.trailingComponent", clearBtn)`), permitindo reset instantâneo da pesquisa.
4. **Atalhos Globais de Teclado**: Foco imediato ao pressionar `Ctrl + F` ou `/` (quando nenhum outro campo de texto estiver focado), e limpeza/desfoque com `Esc`.
5. **Debounce Inteligente**: O `DocumentListener` aciona a filtragem em tempo real com debounce de 100ms para evitar recomposições excessivas de lista durante digitação rápida.

---

### 2.3 Sub-Header e Gestão de Coleção

#### Estado Atual (`LibraryScreen.kt:222-226`)
A tela atual exibe apenas um `JLabel("Biblioteca")` estático no topo esquerdo. A quantidade de obras filtradas ou totais só é exibida na barra de status inferior da janela (`ctx.setStatus`), exigindo que o olhar do usuário viaje até a base da tela para saber quantas obras existem na categoria.

#### Solução Baseada na Referência Libib (Foto 1)
O Libib introduz uma barra de sub-cabeçalho altamente ergonômica:
```
[ My Books  [ 112 ]  > ]
```
1. **Título da Categoria / Coleção Ativa**: Exibe o nome da coleção corrente (ex: "Biblioteca", "Lendo", "Completos", "Quero ler" ou categorias personalizadas).
2. **Work Count Pill Badge**:
   - Um contêiner arredondado (pílula) desenhado ao lado do título com cor de fundo suave (`Theme.ACCENT_SOFT` ou cinza neutro com transparência).
   - Tipografia em negrito (`Font.BOLD, 11px`).
   - Texto dinâmico: quando não há busca ativa, exibe o total (ex: `112`); quando uma busca ou filtro alfabético restringe a exibição, exibe a contagem contextual (ex: `14 / 112`).
3. **Dropdown Navigation / Chevron**: Um botão sutil com ícone `chevronRight` ou `arrowDropDown` que permite abrir diretamente o menu de alternância de coleções ou adicionar nova coleção sem precisar navegar para outras telas.

---

### 2.4 Controles de Exibição, Ordenação e Filtros

#### Estado Atual (`LibraryScreen.kt:165-205`)
Todas as configurações de visualização, filtros e ordenação estão misturadas em um único `JPopupMenu` vinculado ao botão de filtro:
- Checkbox `Somente disponíveis offline`;
- Checkbox `Grade compacta (título sobre capa)`;
- Rótulo estático `ORDENAR POR:`;
- Radio buttons para `Título (A-Z)`, `Não lidos`, `Recentes`.
- **Limitação grave**: Não existe controle de direção (apenas ordenação ascendente fixa). O usuário não pode ordenar de Z a A, nem ordenar por "Menos lidos" ou "Mais antigos".

#### Solução Baseada na Referência Libib (Foto 1)
A barra de sub-cabeçalho separa as intenções do usuário em 3 blocos de controle de fácil acesso à direita:

1. **Seletor de Modo de Exibição (`DisplayModeButton`)**:
   - Botão pill estilo FlatLaf: `[ ▦ Grade compacta ▼ ]`.
   - Clique abre popup com os 4 modos padrão do ecossistema Mihon:
     1. `✓ Grade compacta (título sobre capa)` — **PADRÃO DE FÁBRICA OBRIGATÓRIO**;
     2. `Grade confortável` (título em caixa abaixo da capa);
     3. `Apenas capa` (sem títulos, densidade máxima);
     4. `Lista detalhada` (modo horizontal com sinopse, capítulos e metadados).
2. **Seletor de Ordenação com Alternância de Sentido (`SortControlGroup`)**:
   - **Botão de Critério `[ ≡ Título ]`**: Abre menu suspenso com os critérios:
     - Título Alfabético;
     - Capítulos Não Lidos;
     - Data de Adição;
     - Último Lido;
     - Total de Capítulos.
   - **Botão de Sentido Anexo `[ ↓ ]` / `[ ↑ ]`**: Botão compacto imediatamente à direita do critério. Um clique inverte o sentido instantaneamente (`ASCENDING` $\leftrightarrow$ `DESCENDING`), atualizando o ícone de seta e a lista em tempo real.
3. **Botão de Filtros em Destaque (`FiltersButton`)**:
   - Inspirado no Libib (onde o botão `[ ☵ Filters ]` possui preenchimento colorido em destaque teal/cyan).
   - Quando não há filtros ativos: botão neutro com contorno ou fundo suave `[ ☵ Filtros ]`.
   - Quando há filtros ativos (ex: offline ativo ou status selecionado): preenchimento em cor de destaque (`Theme.ACCENT`) com texto em alto contraste e indicador numérico `[ ☵ Filtros (1) ]`.
   - Pop-up com switches FlatLaf:
     - `Somente disponíveis offline` (toggle);
     - `Ocultar mangás já lidos` (toggle);
     - `Filtrar por status` (Lendo, Completos, Quero ler).

---

### 2.5 Barra de Navegação Alfabética (Libib Jump Bar)

#### Estado Atual (`LibraryScreen.kt`)
Inexistente. Em coleções com mais de 50 ou 100 mangás, localizar uma obra que começa com a letra "S" exige rodar a roda do mouse repetidamente ou arrastar a barra de rolagem até a posição estimada.

#### Solução Baseada na Referência Libib (Foto 1)
Uma barra horizontal esbelta posicionada logo abaixo do sub-header:
```
ALL   #   A   B   C   D   E   F   G   H   I   J   K   L   M   N   O   P   Q   R   S   T   U   V   W   X   Y   Z
```
1. **Comportamento Interativo**:
   - Cada caractere é um item interativo com cursor de mão (`Cursor.HAND_CURSOR`) e efeito de hover sutil com cantos arredondados (`roundRect`).
   - O item `ALL` limpa o filtro alfabético e restaura a visualização integral.
   - O caractere `#` filtra títulos iniciados por dígitos ou caracteres especiais (0-9, símbolos).
   - As letras `A` até `Z` realizam rolagem imediata (`grid.ensureIndexIsVisible`) para o primeiro mangá cujo título inicie com a letra selecionada, ou filtram a exibição para aquela letra específica.
2. **Feedback Visual de Densidade**:
   - Letras que possuem ao menos 1 obra na coleção ativa aparecem em `Theme.TEXT_PRIMARY` com opacidade total.
   - Letras sem nenhuma obra correspondente aparecem esmaecidas (`Theme.TEXT_MUTED` com 40% de opacidade) e com cursor padrão, evitando frustração de cliques vazios.
   - A letra ou filtro ativo recebe fundo arredondado preenchido na cor de destaque (`Theme.ACCENT`) com texto em alto contraste.

---

### 2.6 Modo Padrão de Exibição: "Grade Compacta (Título sobre Capa)"

#### Justificativa Técnica e Alinhamento
Na Foto 1 (`media_1794097903700_99826d00.png`), a opção `Grade compacta (título sobre capa)` é expressamente destacada com marca de seleção ativa (`✓`). No código atual de `LibraryScreen.kt:59`:
```kotlin
private var compactMode: Boolean = false // PROBLEMA: Vem desativado por padrão
```
No Mihon Android upstream (`tachiyomi/domain/library/model/LibraryDisplayMode.kt`):
```kotlin
sealed class LibraryDisplayMode(val flag: Long) {
    object CompactGrid : LibraryDisplayMode(0b00000000)
    object ComfortableGrid : LibraryDisplayMode(0b00000001)
    object List : LibraryDisplayMode(0b00000010)
    object CoverOnlyGrid : LibraryDisplayMode(0b00000011)

    companion object {
        val values by lazy { setOf(CompactGrid, ComfortableGrid, List, CoverOnlyGrid) }
        val default = CompactGrid // UPSTREAM: CompactGrid é o padrão oficial
    }
}
```
A especificação deste plano determina:
1. **Factory Default Imutável**: Ao abrir o Mihon Linux pela primeira vez (ou quando não houver preferência salva em `Preferences`), o modo inicial é obrigatoriamente `LibraryDisplayMode.COMPACT_GRID` (`compactMode = true`).
2. **Otimização de Geometria de Grade**:
   - No modo confortável, a célula consome `326px` de altura (`CARD_H = COVER_H + 74`), dos quais 74px são um rodapé de texto abaixo da imagem.
   - No modo compacto, a altura da célula é reduzida para `268px` (`COVER_H + 16px` de respiro de elevação), eliminando os 74px vazios.
   - **Ganho de densidade**: Em uma tela Full HD típica (1920x1080), o modo compacto permite exibir até 30% a 40% mais capas visíveis simultaneamente sem necessidade de rolagem.

---

### 2.7 Renderização de Cartões e Posters (`MangaPosterCard.kt`)

#### Comparativo Visual e Mecânico

```
MODO CONFORTÁVEL (Atual)             MODO GRADE COMPACTA (Libib / Foto 1 / Upstream)
+-----------------------+            +-----------------------+
| [⬇ 2]          [PT-BR]|            | [⬇ 2]          [PT-BR]|
|                       |            |                       |
|                       |            |                       |
|      Arte Capa        |            |      Arte Capa        |
|      (252px)          |            |      (252px)          |
|                       |            |                       |
|                       |            | [=== Scrim Gradient =]|
+-----------------------+            | Solo Leveling         |
| Solo Leveling         |            +-----------------------+
| Cap. 179              |            (Altura compacta: 268px - título
+-----------------------+             sobre a capa com scrim e sombra)
(Altura total: 326px)
```

#### Especificação do Scrim e Sombra de Texto
Para garantir 100% de legibilidade do título sobre capas claras, brancas ou coloridas (como capas de mangás shoujo ou edições especiais com fundo branco):
1. **Gradiente Inferior (Scrim)**:
   - Inicia em 35% da altura inferior da capa (`gradH = 82px`).
   - Gradiente de `Color(0, 0, 0, 0)` (transparência total no topo) até `Color(8, 8, 10, 235)` (preto profundo suave na base).
   - Recorte perfeito nos cantos inferiores da capa utilizando `RoundRectangle2D` com raio idêntico ao raio da capa (`COVER_ARC = 10px`).
2. **Dupla Passagem de Renderização de Texto (Text Shadow)**:
   - Fonte: `Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)`.
   - Passagem 1 (Sombra / Contorno): Desenha o texto deslocado em 1px em cruz (`(x-1, y)`, `(x+1, y)`, `(x, y-1)`, `(x, y+1)`) com cor preta semitransparente `Color(0, 0, 0, 220)`.
   - Passagem 2 (Texto Principal): Desenha o texto com branco puro `Color(255, 255, 255)` na posição `(x, y)`.
   - Quebra de linha inteligente em até 2 linhas (`wrapTwoLines`) com elipse (`…`) se o título ultrapassar o limite.

---

## 3. Diagramas Arquiteturais e Hierarquia Visual

### 3.1 Wireframe ASCII da Interface Desktop Completa

```
+========================================================================================================+
| TOP APP BAR (Barra Superior de Busca Persistente e Ações Rápidas)                                       |
| [ Mihon Linux ]              [ 🔍 Buscar na biblioteca... (Ctrl+F)          ✖ ]        [ ↻ ]  [ 📦+ ]  |
+========================================================================================================+
| SUB-HEADER / GESTÃO DA COLEÇÃO (Estilo Libib Foto 1)                                                   |
| [ Biblioteca  [ 112 ]  ▼ ]                [ ▦ Grade compacta ▼ ]   [ ≡ Título ] [ ↓ ]   [ ☵ Filtros ]  |
+========================================================================================================+
| ALPHABET JUMP BAR (Barra de Salto Alfabético Rápido)                                                   |
| [ ALL ]  #   A   B   C   D   E   F   G   H   I   J   K   L   M   N   O   P   Q   R   S   T  ...  Y   Z   |
+========================================================================================================+
| CATEGORY TABS BAR (Abas de Categorias com Pílulas de Contagem)                                         |
| [ Todos (112) ]*    [ Lendo (42) ]    [ Completos (58) ]    [ Quero ler (12) ]                         |
+========================================================================================================+
| GRADE DE MANGÁS (JList HORIZONTAL_WRAP - Default: Grade Compacta com Título Sobre Capa)                 |
|                                                                                                        |
|   +-------------------+   +-------------------+   +-------------------+   +-------------------+        |
|   | [⬇ 3]     [PT-BR] |   | [5]        [LOCAL]|   |             [EN]  |   | [1]        [PT-BR]|        |
|   |                   |   |                   |   |                   |   |                   |        |
|   |                   |   |                   |   |                   |   |                   |        |
|   |   (Arte Capa)     |   |   (Arte Capa)     |   |   (Arte Capa)     |   |   (Arte Capa)     |        |
|   |                   |   |                   |   |                   |   |                   |        |
|   | [===Scrim Black==]|   | [===Scrim Black==]|   | [===Scrim Black==]|   | [===Scrim Black==]|        |
|   | Solo Leveling     |   | Berserk           |   | Chainsaw Man      |   | One Piece         |        |
|   +-------------------+   +-------------------+   +-------------------+   +-------------------+        |
|                                                                                                        |
|   +-------------------+   +-------------------+   +-------------------+   +-------------------+        |
|   | [⬇ 12]    [PT-BR] |   | [2]        [PT-BR]|   | [4]         [EN]  |   |            [LOCAL]|        |
|   |                   |   |                   |   |                   |   |                   |        |
|   |   (Arte Capa)     |   |   (Arte Capa)     |   |   (Arte Capa)     |   |   (Arte Capa)     |        |
|   |                   |   |                   |   |                   |   |                   |        |
|   | [===Scrim Black==]|   | [===Scrim Black==]|   | [===Scrim Black==]|   | [===Scrim Black==]|        |
|   | Vagabond          |   | Vinland Saga      |   | Spy x Family      |   | Monster           |        |
|   +-------------------+   +-------------------+   +-------------------+   +-------------------+        |
|                                                                                                        |
+========================================================================================================+
| STATUS BAR                                                                                             |
| Biblioteca: 112 mangás • Grade compacta ativa                                                          |
+========================================================================================================+
```

---

### 3.2 Árvore de Componentes Swing (Hierarchy Tree)

```
LibraryScreen (JPanel, BorderLayout)
│
├── topHeaderContainer (JPanel, BorderLayout) [NORTH]
│   │
│   ├── modernTopBar (JPanel, BorderLayout) [NORTH]
│   │   ├── appTitleLabel: JLabel ("Biblioteca" ou Logotipo Mihon) [WEST]
│   │   ├── searchContainer: JPanel (FlowLayout) [CENTER]
│   │   │   └── searchField: FlatSearchField (JTextField com leading/trailing icons)
│   │   └── quickActionsPanel: JPanel (FlowLayout) [EAST]
│   │       ├── btnSyncLibrary: JButton ("↻ Atualizar biblioteca")
│   │       └── btnImportLocal: JButton ("📦 Adicionar Mangá")
│   │
│   ├── subHeaderToolbar (JPanel, BorderLayout) [CENTER]
│   │   ├── collectionMetaPanel: JPanel (FlowLayout) [WEST]
│   │   │   ├── collectionTitleLabel: JLabel ("Todos os Mangás")
│   │   │   ├── workCountPillBadge: WorkCountBadge (Custom Painted Pill)
│   │   │   └── collectionDropdownBtn: JButton ("▼")
│   │   └── collectionControlsPanel: JPanel (FlowLayout) [EAST]
│   │       ├── displayModeBtn: JToggleButton / JButton ("[ ▦ Grade compacta ▼ ]")
│   │       ├── sortCriterionBtn: JButton ("[ ≡ Título ]")
│   │       ├── sortDirectionBtn: JButton ("[ ↓ ]" / "[ ↑ ]")
│   │       └── filtersBtn: JButton ("[ ☵ Filtros ]" ou "[ ☵ Filtros (2) ]")
│   │
│   ├── alphabetBar: LibraryAlphabetBar (Custom JPanel) [SOUTH]
│   │   └── letterItems: List<AlphabetButton> ("ALL", "#", "A".."Z")
│   │
│   └── tabsBar: LibraryTabsBar (Custom Scrollable Tabs) [SOUTH of Header]
│       └── categoryTabs: List<CategoryTabItem>
│
└── scrollPane: JScrollPane [CENTER]
    └── grid: JList<Manga> (layoutOrientation = HORIZONTAL_WRAP)
        └── cellRenderer: MangaPosterCard (com renderização de elevação, capas e badges)
```

---

### 3.3 Diagrama de Fluxo de Estados e Eventos

```
                   +-----------------------------------+
                   |         USUÁRIO / INPUT           |
                   +-----------------------------------+
                     │         │           │        │
      Digitação no   │  Troca  │ Alterna   │ Clica  │ Seleciona
       searchField   │  Modo   │  Sentido  │ Filtro │  Letra
                     ▼         ▼           ▼        ▼       ▼
       +──────────────────────────────────────────────────────────+
       |               DISPATCH DE AÇÕES (ACTIONS)                |
       |  - SetSearchQuery(query)                                 |
       |  - SetDisplayMode(mode)                                  |
       |  - SetSort(type, direction)                              |
       |  - ToggleOfflineFilter / ToggleUnreadFilter              |
       |  - SelectAlphabetLetter(letter)                          |
       +──────────────────────────────────────────────────────────+
                                   │
                                   ▼
       +──────────────────────────────────────────────────────────+
       |               LIBRARY REDUCER / STATE HOLDER             |
       |            Produz novo `LibraryViewState` imutável       |
       +──────────────────────────────────────────────────────────+
                                   │
                                   ▼
       +──────────────────────────────────────────────────────────+
       |                   PIPELINE DE DADOS                      |
       | 1. Filtro por Categoria Ativa (`status == cat`)          |
       | 2. Filtro Offline (`downloadedCounts[id] > 0`)            |
       | 3. Filtro Alfabético (`title.startsWith(letter)`)         |
       | 4. Filtro de Busca Textual (`title.contains(query)`)      |
       | 5. Ordenação Bidirecional (`comparator` com `direction`)  |
       +──────────────────────────────────────────────────────────+
                                   │
                                   ▼
       +──────────────────────────────────────────────────────────+
       |                  ATUALIZAÇÃO DE UI                       |
       | - `workCountPillBadge.setCounts(shown.size, total.size)` |
       | - `grid.fixedCellHeight = displayMode.cellHeight`        |
       | - `model.replaceAll(shownList)`                          |
       | - `grid.repaint()`                                       |
       +──────────────────────────────────────────────────────────+
```

---

## 4. Especificação Técnica Detalhada dos Componentes de UI

### 4.1 Componente: Top Bar com Busca Rápida Persistente (`FlatSearchField`)

O campo de busca não deve ser apenas um `JTextField` padrão, mas sim um componente moderno de design system integrado ao FlatLaf:

```kotlin
class FlatSearchField(
    placeholder: String = "Buscar na biblioteca (Ctrl+F)…",
    private val onQueryChanged: (String) -> Unit,
) : JTextField() {

    private val clearButton = JButton().apply {
        icon = ModernIcons.clear(14) { Theme.TEXT_MUTED }
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Limpar busca (Esc)"
        isVisible = false
        addActionListener {
            text = ""
            onQueryChanged("")
            requestFocusInWindow()
        }
    }

    init {
        font = Theme.FONT_UI.deriveFont(13f)
        putClientProperty("JTextField.placeholderText", placeholder)
        putClientProperty("JTextField.leadingIcon", ModernIcons.search(16) { Theme.TEXT_MUTED })
        putClientProperty("JTextField.trailingComponent", clearButton)
        putClientProperty("JComponent.roundRect", true)
        putClientProperty("TextComponent.arc", 24)
        
        preferredSize = Dimension(380, 36)
        minimumSize = Dimension(240, 36)

        // Atualização reativa com debounce
        document.addDocumentListener(object : DocumentListener {
            private var timer: javax.swing.Timer? = null

            private fun trigger() {
                clearButton.isVisible = text.isNotEmpty()
                timer?.stop()
                timer = javax.swing.Timer(100) {
                    onQueryChanged(text.trim())
                }.apply { isRepeats = false; start() }
            }

            override fun insertUpdate(e: DocumentEvent) = trigger()
            override fun removeUpdate(e: DocumentEvent) = trigger()
            override fun changedUpdate(e: DocumentEvent) = trigger()
        })

        // Atalhos de teclado
        registerKeyboardAction(
            { text = ""; onQueryChanged(""); transferFocus() },
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            WHEN_FOCUSED,
        )
    }
}
```

#### Regras de Registro de Atalho Global
No painel raiz da janela (`rootPane`), registra-se o atalho global `Ctrl+F` e `/`:
```kotlin
rootPane.registerKeyboardAction(
    { searchField.requestFocusInWindow(); searchField.selectAll() },
    KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx),
    JComponent.WHEN_IN_FOCUSED_WINDOW
)
```

---

### 4.2 Componente: Sub-Header de Gestão de Coleção e Work Count Pill Badge

O sub-header replica a experiência do Libib (Foto 1), trazendo à esquerda a identidade da coleção e a densidade de títulos.

```kotlin
class CollectionHeaderPanel(
    private var title: String,
    private var totalCount: Int,
    private var filteredCount: Int = totalCount,
    private val onDropdownClick: (JButton) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {

    private val titleLabel = JLabel(title).apply {
        font = Theme.FONT_DISPLAY.deriveFont(Font.BOLD, 17f)
        foreground = Theme.TEXT_PRIMARY
    }

    private val countBadge = WorkCountPillBadge(totalCount, filteredCount)

    private val dropdownBtn = JButton().apply {
        icon = ModernIcons.chevronDown(14) { Theme.TEXT_MUTED }
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(22, 22)
        addActionListener { onDropdownClick(this) }
    }

    init {
        isOpaque = false
        add(titleLabel)
        add(countBadge)
        add(dropdownBtn)
    }

    fun updateData(newTitle: String, total: Int, filtered: Int) {
        titleLabel.text = newTitle
        countBadge.setCounts(total, filtered)
        revalidate()
        repaint()
    }
}
```

#### Renderização do `WorkCountPillBadge`
```kotlin
class WorkCountPillBadge(
    private var total: Int,
    private var filtered: Int = total,
) : JComponent() {

    init {
        font = Theme.FONT_UI.deriveFont(Font.BOLD, 11f)
        isOpaque = false
    }

    fun setCounts(newTotal: Int, newFiltered: Int) {
        total = newTotal
        filtered = newFiltered
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val text = formatText()
        val w = fm.stringWidth(text) + 16
        return Dimension(w, 22)
    }

    private fun formatText(): String {
        return if (filtered == total || filtered < 0) "$total" else "$filtered / $total"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Fundo pílula arredondada
        val pillColor = if (filtered != total) Theme.ACCENT_SOFT else Color(128, 128, 128, 45)
        g2.color = pillColor
        g2.fillRoundRect(0, 1, width, height - 2, height - 2, height - 2)

        // Borda suave
        g2.color = if (filtered != total) Theme.ACCENT else Color(128, 128, 128, 70)
        g2.drawRoundRect(0, 1, width - 1, height - 3, height - 2, height - 2)

        // Texto
        val text = formatText()
        val fm = g2.fontMetrics
        val textX = (width - fm.stringWidth(text)) / 2
        val textY = (height - fm.height) / 2 + fm.ascent
        g2.color = if (filtered != total) Theme.ACCENT else Theme.TEXT_SECONDARY
        g2.drawString(text, textX, textY)
        g2.dispose()
    }
}
```

---

### 4.3 Componente: Controles Modernos de Exibição, Ordenação e Filtros

#### 1. Seletor de Modo de Exibição (`displayModeBtn`)
- **Aparência**: Botão estilo FlatLaf `[ ▦ Grade compacta ▼ ]` com ícone vetorial de 4 quadrados.
- **Menu Popup**:
  - `✓ Grade compacta (título sobre capa)` (marcado com checkmark em negrito).
  - `Grade confortável`
  - `Apenas capa`
  - `Lista detalhada`
- **Ação**: Ao selecionar, persiste a preferência em `Preferences` e recalcula `fixedCellHeight` e `fixedCellWidth` na `JList`.

#### 2. Seletor de Ordenação com Sentido Anexo
- **Botão de Critério (`sortCriterionBtn`)**:
  - Rótulo exibe o critério atual: `[ ≡ Título ]`, `[ ≡ Não lidos ]`, `[ ≡ Recentes ]`, `[ ≡ Capítulos ]`.
  - Abre menu popup para troca de critério.
- **Botão de Direção (`sortDirectionBtn`)**:
  - Imediatamente colado ao botão de critério (botão compacto `28x28px`).
  - Quando `SortDirection.ASCENDING`: ícone `[ ↓ ]` (ou seta para cima A-Z), tooltip "Ordem ascendente (clique para inverter)".
  - Quando `SortDirection.DESCENDING`: ícone `[ ↑ ]`, tooltip "Ordem descendente (clique para inverter)".
  - Ação: inverte o enum `SortDirection` e executa nova ordenação sem recarregar do banco de dados.

#### 3. Botão de Filtros em Destaque (`filtersBtn`)
- **Estado Neutro**: Fundo transparente/superfície, ícone de 3 controles deslizantes `[ ☵ Filtros ]`.
- **Estado Ativo**: Se houver filtros ativos (ex: offline = true ou status específico):
  - Fundo preenchido com `Theme.ACCENT` (ou `ACCENT_SOFT`);
  - Rótulo dinâmico: `[ ☵ Filtros (1) ]` ou `[ ☵ Filtros (2) ]`.
- **Pop-up de Filtros**:
  - Switch FlatLaf: `Somente disponíveis offline`.
  - Switch FlatLaf: `Somente não lidos`.
  - Grupo de status: `Lendo`, `Completos`, `Quero ler`.
  - Ação de rodapé: botão texto `Limpar filtros`.

---

### 4.4 Componente: Barra de Navegação Alfabética (`LibraryAlphabetBar`)

Inspirada no Libib (Foto 1), posiciona-se logo abaixo do sub-header.

```kotlin
class LibraryAlphabetBar(
    private val onLetterSelected: (String?) -> Unit,
) : JPanel() {

    private val letters = listOf("ALL", "#") + ('A'..'Z').map { it.toString() }
    private var activeLetter: String? = "ALL"
    private var availableLetters: Set<String> = emptySet()

    init {
        layout = FlowLayout(FlowLayout.CENTER, 3, 2)
        isOpaque = false
        preferredSize = Dimension(Short.MAX_VALUE.toInt(), 30)
        rebuildButtons()
    }

    fun setAvailableLetters(available: Set<String>) {
        availableLetters = available
        rebuildButtons()
    }

    fun setActiveSelection(letter: String?) {
        activeLetter = letter ?: "ALL"
        repaint()
    }

    private fun rebuildButtons() {
        removeAll()
        letters.forEach { letter ->
            val isEnabled = letter == "ALL" || letter == "#" || availableLetters.contains(letter)
            val btn = AlphabetCharButton(letter, isEnabled) {
                activeLetter = if (letter == "ALL") null else letter
                onLetterSelected(activeLetter)
                repaint()
            }
            add(btn)
        }
        revalidate()
        repaint()
    }

    private inner class AlphabetCharButton(
        val charText: String,
        val enabledState: Boolean,
        val onClick: () -> Unit,
    ) : JComponent() {

        private var hovered = false

        init {
            cursor = if (enabledState) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            font = Theme.FONT_UI.deriveFont(Font.BOLD, 10.5f)
            val fm = getFontMetrics(font)
            val w = maxOf(fm.stringWidth(charText) + 8, 22)
            preferredSize = Dimension(w, 24)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (enabledState) { hovered = true; repaint() }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (enabledState) { hovered = false; repaint() }
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (enabledState && SwingUtilities.isLeftMouseButton(e)) {
                        onClick()
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            val isCurrent = (charText == "ALL" && (activeLetter == null || activeLetter == "ALL")) ||
                (charText == activeLetter)

            if (isCurrent) {
                g2.color = Theme.ACCENT
                g2.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
            } else if (hovered && enabledState) {
                g2.color = Color(128, 128, 128, 40)
                g2.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
            }

            val fm = g2.fontMetrics
            val tx = (width - fm.stringWidth(charText)) / 2
            val ty = (height - fm.height) / 2 + fm.ascent

            g2.color = when {
                isCurrent -> Color.WHITE
                !enabledState -> Theme.TEXT_MUTED.let { Color(it.red, it.green, it.blue, 75) }
                hovered -> Theme.TEXT_PRIMARY
                else -> Theme.TEXT_SECONDARY
            }
            g2.drawString(charText, tx, ty)
            g2.dispose()
        }
    }
}
```

---

### 4.5 Componente: Grade de Posters e Renderização Compacta Avançada (`MangaPosterCard`)

#### Parâmetros de Geometria e Layout da Grade
- **Dimensões Físicas do Livro**:
  - `COVER_W = 176`
  - `COVER_H = 252`
  - `COVER_ARC = 10`
- **Dimensões da Célula na `JList`**:
  - No modo **`COMPACT_GRID` (Padrão de Fábrica)**:
    - `fixedCellWidth = 188` (proporciona 12px de vão horizontal entre livros)
    - `fixedCellHeight = 268` (proporciona 16px de vão vertical e elevação de sombra)
  - No modo **`COMFORTABLE_GRID`**:
    - `fixedCellWidth = 188`
    - `fixedCellHeight = 326` (adiciona a caixa de texto de 74px)

#### Algoritmo de Pintura do Modo Grade Compacta
```kotlin
// Em MangaPosterCard.paintComponent(g):
if (compactOverlayMode) {
    // 1. Scrim Gradiente na base da capa (cobre os últimos 82px)
    val gradH = 82
    val gradY = coverY + COVER_H - gradH
    val scrimPaint = GradientPaint(
        0f, gradY.toFloat(), Color(0, 0, 0, 0),
        0f, (coverY + COVER_H).toFloat(), Color(8, 8, 12, 240)
    )
    
    // Recorte com cantos arredondados na base da capa
    val oldClip = g2.clip
    val coverShape = RoundRectangle2D.Float(
        coverX.toFloat(), coverY.toFloat(),
        COVER_W.toFloat(), COVER_H.toFloat(),
        COVER_ARC.toFloat(), COVER_ARC.toFloat()
    )
    g2.clip(coverShape)
    g2.paint = scrimPaint
    g2.fillRect(coverX, gradY, COVER_W, gradH)
    g2.clip = oldClip

    // 2. Título Tipográfico sobre a Capa com Dupla Sombra
    g2.font = Theme.FONT_UI.deriveFont(Font.BOLD, 12.5f)
    val maxTextW = COVER_W - 14
    val lines = wrapTwoLines(g2, titleText, maxTextW)
    
    val lineH = 15
    var ty = coverY + COVER_H - (if (lines.size == 2) 28 else 14)

    lines.forEach { line ->
        val textX = coverX + 8
        // Sombra de alto contraste em cruz
        g2.color = Color(0, 0, 0, 220)
        g2.drawString(line, textX - 1, ty)
        g2.drawString(line, textX + 1, ty)
        g2.drawString(line, textX, ty - 1)
        g2.drawString(line, textX, ty + 1)
        
        // Texto frontal branco puro
        g2.color = Color.WHITE
        g2.drawString(line, textX, ty)
        ty += lineH
    }
}
```

---

## 5. Modelos de Dados, Contratos de Interface e Gerenciamento de Estado

### 5.1 Enums Estruturados

```kotlin
package mihon.desktop

/**
 * Modos de exibição suportados pela aba Biblioteca.
 * O modo COMPACT_GRID é mandatória e arquiteturalmente o padrão de fábrica.
 */
enum class LibraryDisplayMode(val id: String, val label: String) {
    COMPACT_GRID("compact_grid", "Grade compacta (título sobre capa)"),
    COMFORTABLE_GRID("comfortable_grid", "Grade confortável"),
    COVER_ONLY("cover_only", "Apenas capa"),
    DETAILED_LIST("detailed_list", "Lista detalhada");

    companion object {
        /** PADRÃO OBRIGATÓRIO DE FÁBRICA */
        val DEFAULT = COMPACT_GRID

        fun fromId(id: String?): LibraryDisplayMode {
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * Critérios de ordenação suportados.
 */
enum class LibrarySortType(val id: String, val label: String) {
    TITLE("title", "Título"),
    UNREAD("unread", "Não lidos"),
    DATE_ADDED("date_added", "Data de adição"),
    LAST_READ("last_read", "Último lido"),
    TOTAL_CHAPTERS("total_chapters", "Total de capítulos");

    companion object {
        val DEFAULT = TITLE

        fun fromId(id: String?): LibrarySortType {
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * Direção de ordenação.
 */
enum class SortDirection {
    ASCENDING,
    DESCENDING;

    fun toggle(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING

    companion object {
        val DEFAULT = ASCENDING
    }
}
```

---

### 5.2 Estado Completo da Tela (`LibraryViewState`)

```kotlin
package mihon.desktop

import tachiyomi.domain.manga.model.Manga

/**
 * Estado imutável da aba Biblioteca.
 */
data class LibraryViewState(
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.DEFAULT,
    val sortType: LibrarySortType = LibrarySortType.DEFAULT,
    val sortDirection: SortDirection = SortDirection.DEFAULT,
    val searchQuery: String = "",
    val activeCategory: Long = LibraryCategory.ALL,
    val selectedLetter: String? = null,
    val onlyOffline: Boolean = false,
    val onlyUnread: Boolean = false,
    val allMangas: List<Manga> = emptyList(),
    val filteredMangas: List<Manga> = emptyList(),
    val unreadCounts: Map<Long, Int> = emptyMap(),
    val downloadedCounts: Map<Long, Int> = emptyMap(),
    val availableLetters: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val totalCount: Int get() = allMangas.size
    val filteredCount: Int get() = filteredMangas.size
    val hasActiveFilters: Boolean get() = onlyOffline || onlyUnread || selectedLetter != null || searchQuery.isNotBlank()
    val activeFiltersCount: Int get() {
        var count = 0
        if (onlyOffline) count++
        if (onlyUnread) count++
        if (selectedLetter != null) count++
        return count
    }
}
```

---

### 5.3 Contrato de Ações e Redutor de Estado

```kotlin
sealed interface LibraryAction {
    data class LoadLibrarySuccess(
        val mangas: List<Manga>,
        val unread: Map<Long, Int>,
        val downloaded: Map<Long, Int>
    ) : LibraryAction
    data class SetSearchQuery(val query: String) : LibraryAction
    data class SetCategory(val categoryId: Long) : LibraryAction
    data class SetDisplayMode(val mode: LibraryDisplayMode) : LibraryAction
    data class SetSortType(val sortType: LibrarySortType) : LibraryAction
    data object ToggleSortDirection : LibraryAction
    data class SetAlphabetLetter(val letter: String?) : LibraryAction
    data class SetOfflineFilter(val enabled: Boolean) : LibraryAction
    data class SetUnreadFilter(val enabled: Boolean) : LibraryAction
    data object ClearFilters : LibraryAction
}
```

#### Pipeline de Filtragem e Ordenação
```kotlin
fun computeFilteredList(state: LibraryViewState): List<Manga> {
    val query = state.searchQuery.trim().lowercase()
    val cat = state.activeCategory
    val letter = state.selectedLetter?.uppercase()
    val sort = state.sortType
    val dir = state.sortDirection

    var stream = state.allMangas.asSequence()

    // 1. Categoria
    if (cat != LibraryCategory.ALL) {
        stream = stream.filter { it.status == cat }
    }

    // 2. Filtro Offline
    if (state.onlyOffline) {
        stream = stream.filter { (state.downloadedCounts[it.id] ?: 0) > 0 }
    }

    // 3. Filtro Não Lidos
    if (state.onlyUnread) {
        stream = stream.filter { (state.unreadCounts[it.id] ?: 0) > 0 }
    }

    // 4. Salto / Filtro Alfabético
    if (letter != null) {
        stream = if (letter == "#") {
            stream.filter { it.title.firstOrNull()?.isLetter() == false }
        } else {
            stream.filter { it.title.startsWith(letter, ignoreCase = true) }
        }
    }

    // 5. Busca Textual
    if (query.isNotEmpty()) {
        stream = stream.filter { it.title.lowercase().contains(query) }
    }

    // 6. Ordenação Bidirecional
    val comparator: Comparator<Manga> = when (sort) {
        LibrarySortType.TITLE -> compareBy { it.title.lowercase() }
        LibrarySortType.UNREAD -> compareBy<Manga> { state.unreadCounts[it.id] ?: 0 }.thenBy { it.title.lowercase() }
        LibrarySortType.DATE_ADDED -> compareBy { it.dateAdded }
        LibrarySortType.LAST_READ -> compareBy { it.lastRead }
        LibrarySortType.TOTAL_CHAPTERS -> compareBy { it.totalChapters }
    }

    val finalComparator = if (dir == SortDirection.DESCENDING) comparator.reversed() else comparator
    return stream.sortedWith(finalComparator).toList()
}
```

---

### 5.4 Persistência de Preferências do Usuário

As preferências da biblioteca devem ser armazenadas de forma persistente através do nó Java Preferences (`java.util.prefs.Preferences`):
- Nó: `Preferences.userRoot().node("mihon-linux-library")`
- Chaves:
  - `"display_mode"`: String (default: `"compact_grid"`)
  - `"sort_type"`: String (default: `"title"`)
  - `"sort_direction"`: String (default: `"ascending"`)

```kotlin
object LibraryPreferences {
    private val prefs = java.util.prefs.Preferences.userRoot().node("mihon-linux-library")

    var displayMode: LibraryDisplayMode
        get() = LibraryDisplayMode.fromId(prefs.get("display_mode", LibraryDisplayMode.DEFAULT.id))
        set(value) = prefs.put("display_mode", value.id)

    var sortType: LibrarySortType
        get() = LibrarySortType.fromId(prefs.get("sort_type", LibrarySortType.DEFAULT.id))
        set(value) = prefs.put("sort_type", value.id)

    var sortDirection: SortDirection
        get() = if (prefs.get("sort_direction", "ascending") == "descending") SortDirection.DESCENDING else SortDirection.ASCENDING
        set(value) = prefs.put("sort_direction", if (value == SortDirection.DESCENDING) "descending" else "ascending")
}
```

---

## 6. Design System, Integração FlatLaf e Suporte a Temas

### 6.1 Paleta Semântica e Tokens

Todos os componentes da nova Biblioteca devem respeitar o objeto `Theme` do Mihon Linux, garantindo que a interface se adapte dinamicamente sem necessidade de reiniciar a aplicação:

| Token Semântico | AMOLED Dark | Midnight Blue | Graphite Dark | Material 3 Light | Função no Layout Libib |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `Theme.BG_APP` | `#000000` | `#0A0E17` | `#121214` | `#F8F9FA` | Fundo principal da Top Bar, Sub-Header e Grid |
| `Theme.BG_SURFACE` | `#0F0F12` | `#121826` | `#1C1C20` | `#FFFFFF` | Superfície das abas de categoria e menus popup |
| `Theme.TEXT_PRIMARY` | `#F2F2F5` | `#EAEFFB` | `#EDEDF0` | `#1C1B1F` | Títulos ("Biblioteca"), livros e letras ativas |
| `Theme.TEXT_SECONDARY` | `#A0A0AB` | `#9AA8C7` | `#A1A1AA` | `#49454F` | Rótulo de ordenação e letras normais na jump bar |
| `Theme.TEXT_MUTED` | `#52525B` | `#536383` | `#52525E` | `#79747E` | Placeholders, letras sem obras e ícones secundários |
| `Theme.ACCENT` | `#7C5CFC` | `#4C7DFF` | `#705DF2` | `#6750A4` | Letra ativa na jump bar, botão de filtros com badge |
| `Theme.ACCENT_SOFT` | `#251C47` | `#172347` | `#221C42` | `#EADDFF` | Fundo do pill badge de contagem de obras |

### 6.2 Prevenção de Regressões Visuais e Contraste
1. **Transparência Oportuna**: Paineis contêineres intermediários (`searchContainer`, `collectionMetaPanel`, `collectionControlsPanel`) utilizam `isOpaque = false`, evitando a introdução de blocos retangulares pretos ou cinzas em temas claros (eliminando o risco de bugs análogos ao Foto 4 de Configurações).
2. **Escuta de Mudança de Tema**: Registro via `ctx.addThemeListener { ... }` para revalidar paletas de cores, recarregar ícones dinâmicos do `ModernIcons` e disparar `grid.repaint()`.

---

## 7. Roteiro de Implementação e Plano de Migração por Fases

Para a fase subsequente de implementação, o trabalho será dividido em 6 passos incrementais, cada um auditável e isolado:

### Fase 1: Modelos de Dados, Persistência e Factory Default
- **Arquivos**: Criação de `LibraryDisplayMode.kt`, `LibraryPreferences.kt` ou inclusão em `LibraryScreen.kt`.
- **Entrega**: Definição de `COMPACT_GRID` como padrão oficial de fábrica (`val default = COMPACT_GRID`).
- **Verificação**: Testes unitários validando que a ausência de chave em `Preferences` retorna `COMPACT_GRID`.

### Fase 2: Otimização do Modo Compacto em `MangaPosterCard.kt`
- **Arquivos**: `desktop/src/main/kotlin/mihon/desktop/MangaPosterCard.kt`.
- **Entrega**:
  - Implementação da altura compacta de célula `fixedCellHeight = 268` quando `compactOverlayMode = true`.
  - Gradiente scrim escuro refinado (82px) com recorte de cantos inferiores arredondados.
  - Dupla passagem de renderização de sombra de texto para contraste absoluto em capas brancas/claras.
- **Verificação**: Teste visual em capas escuras e capas predominantemente brancas.

### Fase 3: Reestruturação da Top Bar com `FlatSearchField`
- **Arquivos**: `desktop/src/main/kotlin/mihon/desktop/LibraryScreen.kt`.
- **Entrega**:
  - Remoção do botão de busca colapsável e inserção do campo de busca rápido e persistente no centro da Top Bar.
  - Inclusão de atalhos de teclado `Ctrl+F` e `/`.
  - Botão de limpeza rápida integrado (`clearButton`).
- **Verificação**: Digitação imediata na barra filtra o modelo da `JList` em tempo real.

### Fase 4: Sub-Header Libib com Work Count Pill e Controles de Ordenação/Filtro
- **Arquivos**: `desktop/src/main/kotlin/mihon/desktop/LibraryScreen.kt`.
- **Entrega**:
  - Criação da barra de sub-cabeçalho com título da coleção ativa e `WorkCountPillBadge` (`[ 112 ]` ou `[ 14 / 112 ]`).
  - Criação do seletor dedicado de visualização `[ ▦ Grade compacta ▼ ]`.
  - Criação do botão seletor de critério `[ ≡ Título ]` e botão anexo de direção `[ ↓ ]` / `[ ↑ ]`.
  - Criação do botão destacado de filtros com contador de filtros ativos `[ ☵ Filtros ]`.
- **Verificação**: Alternar o botão de direção inverte a ordem instantaneamente; ativar filtro offline destaca o botão de filtro.

### Fase 5: Barra de Índice Alfabético (`LibraryAlphabetBar`)
- **Arquivos**: `desktop/src/main/kotlin/mihon/desktop/LibraryScreen.kt`.
- **Entrega**:
  - Componente horizontal `ALL # A-Z` posicionado abaixo do sub-header.
  - Cálculo dinâmico do conjunto `availableLetters` a partir dos títulos carregados.
  - Ação de clique saltando para a seção correspondente via `grid.ensureIndexIsVisible()`.
- **Verificação**: Clicar na letra "B" rola a grade diretamente para as obras iniciadas por B.

### Fase 6: Testes Automatizados, Verificação e Validação Cruzada
- **Entrega**: Teste automatizado de ordenação bidirecional, persistência de preferências e filtragem combinada.
- **Verificação**: `gradle compileKotlin`, `gradle test`, `gradle :desktop:installDist`.

---

## 8. Critérios de Validação e Verificação Independente

Para validação técnica deste Milestone M1 e auditoria forense:

1. **Integridade Absoluta do Código-Fonte Existente (Regra R1 de Escopo)**:
   ```bash
   cd /home/janlucas/mihon-linux
   git status
   ```
   *Critério de Aprovação*: Nenhuma modificação em `LibraryScreen.kt` ou em qualquer arquivo sob `desktop/` ou `core/`. Apenas o arquivo `LIBRARY_MODERNIZATION_PLAN.md` deve constar como novo arquivo na raiz do repositório.

2. **Compilação Limpa da Base de Código**:
   ```bash
   cd /home/janlucas/mihon-linux
   ./gradlew compileKotlin
   ```
   *Critério de Aprovação*: `BUILD SUCCESSFUL` sem erros de compilação ou incompatibilidade.

3. **Conformidade Documental com a Referência Visual Foto 1**:
   - Inspeção visual de `media_1794097293781_385dbe79.jpg` confirmando a presença de todos os elementos:
     - Top Bar com busca persistente;
     - Sub-Header com Collection Title + Work Count Pill + Chevron;
     - Controles dedicados de Cover Display, Sort Title + Direction Arrow, Highlighted Filters;
     - Alphabetical Jump Bar A-Z # ALL;
     - Grade com capas e títulos integrados.
   - Inspeção visual de `media_1794097903700_99826d00.png` confirmando a exigência mandante de "Grade compacta (título sobre capa)" como padrão de fábrica.
