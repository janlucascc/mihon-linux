package mihon.desktop

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.prefs.Preferences

/**
 * Represents a keyboard shortcut binding with optional modifier mask (Ctrl / Shift / Alt / Meta).
 */
data class KeyCombo(
    val keyCode: Int,
    val modifiers: Int = 0,
) {
    fun normalizedModifiers(): Int =
        modifiers and (InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)

    fun matches(event: KeyEvent): Boolean {
        val eventMods = event.modifiersEx and
            (InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)
        return event.keyCode == keyCode && eventMods == normalizedModifiers()
    }

    fun toDisplayText(): String {
        val mods = normalizedModifiers()
        val parts = mutableListOf<String>()
        if ((mods and InputEvent.CTRL_DOWN_MASK) != 0) parts.add("Ctrl")
        if ((mods and InputEvent.ALT_DOWN_MASK) != 0) parts.add("Alt")
        if ((mods and InputEvent.SHIFT_DOWN_MASK) != 0) parts.add("Shift")
        if ((mods and InputEvent.META_DOWN_MASK) != 0) parts.add("Meta")
        parts.add(KeyEvent.getKeyText(keyCode))
        return parts.joinToString("+")
    }

    fun serialize(): String {
        val mods = normalizedModifiers()
        return if (mods == 0) keyCode.toString() else "$mods:$keyCode"
    }

    companion object {
        fun parse(token: String): KeyCombo? {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return null
            return if (':' in trimmed) {
                val parts = trimmed.split(':', limit = 2)
                val mods = parts[0].toIntOrNull() ?: 0
                val code = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                KeyCombo(code, mods)
            } else {
                val code = trimmed.toIntOrNull()?.takeIf { it > 0 } ?: return null
                KeyCombo(code, 0)
            }
        }
    }
}

/**
 * Customizable keyboard shortcut bindings for ReaderWindow, supporting modifiers and conflict detection.
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

    fun matches(event: KeyEvent): Boolean = getKeyCombos(this).any { it.matches(event) }

    companion object {
        private val prefs = Preferences.userRoot().node("mihon-linux-shortcuts")
        private val cache = java.util.concurrent.ConcurrentHashMap<ReaderAction, Pair<String?, List<KeyCombo>>>()

        fun getKeyCombos(action: ReaderAction): List<KeyCombo> {
            val raw = runCatching { prefs.get(action.name, null) }.getOrNull()
            val cached = cache[action]
            if (cached != null && cached.first == raw) {
                return cached.second
            }
            val resolved = if (raw.isNullOrBlank()) {
                action.defaultKeyCodes.map { KeyCombo(it, 0) }
            } else {
                raw.split(",")
                    .mapNotNull { KeyCombo.parse(it) }
                    .ifEmpty { action.defaultKeyCodes.map { KeyCombo(it, 0) } }
            }
            cache[action] = raw to resolved
            return resolved
        }

        fun setKeyCombos(action: ReaderAction, combos: List<KeyCombo>) {
            val valid = combos.filter { it.keyCode > 0 }.ifEmpty { action.defaultKeyCodes.map { KeyCombo(it, 0) } }
            val serialized = valid.joinToString(",") { it.serialize() }
            cache[action] = serialized to valid
            prefs.put(action.name, serialized)
        }

        fun findConflict(targetAction: ReaderAction, keyCode: Int, modifiers: Int = 0): ReaderAction? {
            val candidate = KeyCombo(keyCode, modifiers)
            val normMods = candidate.normalizedModifiers()
            return entries.firstOrNull { other ->
                other != targetAction && getKeyCombos(other).any {
                    it.keyCode == keyCode && it.normalizedModifiers() == normMods
                }
            }
        }

        fun getKeyCodes(action: ReaderAction): List<Int> = getKeyCombos(action).map { it.keyCode }

        fun setKeyCodes(action: ReaderAction, codes: List<Int>) {
            setKeyCombos(action, codes.filter { it > 0 }.map { KeyCombo(it, 0) })
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
            return getKeyCombos(action).joinToString(" / ") { it.toDisplayText() }
        }

        fun matches(action: ReaderAction, keyCode: Int): Boolean {
            if (keyCode <= 0) return false
            return getKeyCombos(action).any { it.keyCode == keyCode && it.normalizedModifiers() == 0 }
        }

        fun matches(action: ReaderAction, event: KeyEvent): Boolean = action.matches(event)
    }
}
