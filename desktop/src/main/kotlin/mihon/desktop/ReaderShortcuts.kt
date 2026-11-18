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
    ZOOM_RESET("Redefinir Zoom (100%)", "Restaura o zoom ao tamanho padrão", listOf(KeyEvent.VK_0)),
    TOGGLE_TRANSLATOR("Alternar tradutor simultâneo", "Ativa ou desativa a tradução sobreposta estilo Google Lens", listOf(KeyEvent.VK_T));

    val label: String get() = title
    val defaultKeyCode: Int get() = defaultKeyCodes.first()

    fun matches(keyCode: Int): Boolean = Companion.matches(this, keyCode)

    fun matches(event: KeyEvent): Boolean {
        if (event.isControlDown || event.isAltDown || event.isMetaDown || event.isShiftDown) {
            return false
        }
        return Companion.matches(this, event.keyCode)
    }

    companion object {
        private val prefs = Preferences.userRoot().node("mihon-linux-shortcuts")
        private val cache = java.util.concurrent.ConcurrentHashMap<ReaderAction, Pair<String?, List<Int>>>()

        fun getKeyCodes(action: ReaderAction): List<Int> {
            val raw = runCatching { prefs.get(action.name, null) }.getOrNull()
            val cached = cache[action]
            if (cached != null && cached.first == raw) {
                return cached.second
            }
            val resolved = if (raw.isNullOrBlank()) {
                action.defaultKeyCodes
            } else {
                raw.split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 0 }
                    .ifEmpty { action.defaultKeyCodes }
            }
            cache[action] = raw to resolved
            return resolved
        }

        fun setKeyCodes(action: ReaderAction, codes: List<Int>) {
            val validCodes = codes.filter { it > 0 }.ifEmpty { action.defaultKeyCodes }
            val serialized = validCodes.joinToString(",")
            cache[action] = serialized to validCodes
            prefs.put(action.name, serialized)
        }

        fun resetAction(action: ReaderAction) {
            cache.remove(action)
            prefs.remove(action.name)
        }

        fun resetToDefaults() {
            cache.clear()
            entries.forEach { prefs.remove(it.name) }
        }

        fun getDisplayString(action: ReaderAction): String {
            val codes = getKeyCodes(action)
            return codes.joinToString(" / ") { KeyEvent.getKeyText(it) }
        }

        fun matches(action: ReaderAction, keyCode: Int): Boolean {
            if (keyCode <= 0) return false
            return keyCode in getKeyCodes(action)
        }

        fun matches(action: ReaderAction, event: KeyEvent): Boolean = action.matches(event)
    }
}
