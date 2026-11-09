package mihon.desktop

import java.awt.event.KeyEvent
import java.util.prefs.Preferences

/**
 * Customizable keyboard shortcut bindings for ReaderWindow.
 */
enum class ReaderAction(
    val title: String,
    val description: String,
    val defaultKeyCodes: List<Int>,
) {
    NEXT_PAGE("Próxima Página", "Avança uma página ou spread", listOf(KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_DOWN)),
    PREV_PAGE("Página Anterior", "Retorna para a página anterior", listOf(KeyEvent.VK_LEFT, KeyEvent.VK_PAGE_UP)),
    TOGGLE_FULLSCREEN("Alternar Tela Cheia", "Entra ou sai do modo imersivo em tela cheia", listOf(KeyEvent.VK_F11)),
    TOGGLE_AUTO_SCROLL("Auto-Scroll (Mãos-Livres)", "Inicia ou pausa a rolagem contínua suave", listOf(KeyEvent.VK_A)),
    TOGGLE_HUD("Exibir / Ocultar Barra de Menus (HUD)", "Alterna as barras superior e inferior", listOf(KeyEvent.VK_H, KeyEvent.VK_M)),
    NEXT_CHAPTER("Próximo Capítulo", "Avança imediatamente para o próximo capítulo", listOf(KeyEvent.VK_CLOSE_BRACKET)),
    PREV_CHAPTER("Capítulo Anterior", "Volta para o capítulo anterior", listOf(KeyEvent.VK_OPEN_BRACKET)),
    ZOOM_IN("Aumentar Zoom", "Amplia o tamanho da página atual", listOf(KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD)),
    ZOOM_OUT("Diminuir Zoom", "Reduz o tamanho da página atual", listOf(KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT)),
    ZOOM_RESET("Redefinir Zoom (100%)", "Restaura o zoom ao tamanho padrão", listOf(KeyEvent.VK_0));

    companion object {
        private val prefs = Preferences.userRoot().node("mihon-linux-shortcuts")

        fun getKeyCodes(action: ReaderAction): List<Int> {
            val saved = prefs.get(action.name, null) ?: return action.defaultKeyCodes
            return saved.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .ifEmpty { action.defaultKeyCodes }
        }

        fun setKeyCodes(action: ReaderAction, codes: List<Int>) {
            prefs.put(action.name, codes.joinToString(","))
        }

        fun resetToDefaults() {
            entries.forEach { prefs.remove(it.name) }
        }

        fun getDisplayString(action: ReaderAction): String {
            val codes = getKeyCodes(action)
            return codes.joinToString(" / ") { KeyEvent.getKeyText(it) }
        }

        fun matches(action: ReaderAction, keyCode: Int): Boolean {
            return keyCode in getKeyCodes(action)
        }
    }
}
