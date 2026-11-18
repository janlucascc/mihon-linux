package mihon.desktop

import kotlinx.coroutines.launch
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.prefs.Preferences
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Manages the Linux System Tray integration and native desktop notifications (via libnotify / notify-send
 * and AWT TrayIcon fallback).
 */
object SystemTrayManager {
    private val prefs = Preferences.userRoot().node("mihon-linux")
    private var trayIcon: TrayIcon? = null
    private var mainFrameRef: JFrame? = null
    private var onIncognitoToggle: (() -> Unit)? = null

    var minimizeToTray: Boolean
        get() = prefs.getBoolean("minimizeToTray", false)
        set(value) {
            prefs.putBoolean("minimizeToTray", value)
        }

    var desktopNotificationsEnabled: Boolean
        get() = prefs.getBoolean("desktopNotifications", true)
        set(value) {
            prefs.putBoolean("desktopNotifications", value)
        }

    val isSupported: Boolean
        get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    fun install(
        mainFrame: JFrame,
        onResumeLastReading: () -> Unit,
        onCheckUpdates: () -> Unit,
        onIncognito: () -> Unit,
        onExit: () -> Unit,
    ) {
        if (!isSupported || trayIcon != null) return
        mainFrameRef = mainFrame
        onIncognitoToggle = onIncognito
        runCatching {
            val tray = SystemTray.getSystemTray()
            val image = AppIcon.createImage(22)

            val popup = PopupMenu()
            val itemOpen = MenuItem("Abrir Mihon").apply {
                addActionListener {
                    SwingUtilities.invokeLater {
                        restoreFromTray()
                    }
                }
            }
            val itemResume = MenuItem("Continuar Leitura").apply {
                addActionListener {
                    SwingUtilities.invokeLater {
                        restoreFromTray()
                        onResumeLastReading()
                    }
                }
            }
            val itemUpdate = MenuItem("Verificar Atualizações").apply {
                addActionListener {
                    SwingUtilities.invokeLater { onCheckUpdates() }
                }
            }
            val itemIncognito = MenuItem("Modo Anônimo").apply {
                addActionListener {
                    SwingUtilities.invokeLater { onIncognitoToggle?.invoke() }
                }
            }
            val itemExit = MenuItem("Sair").apply {
                addActionListener {
                    SwingUtilities.invokeLater { onExit() }
                }
            }

            popup.add(itemOpen)
            popup.add(itemResume)
            popup.add(itemUpdate)
            popup.addSeparator()
            popup.add(itemIncognito)
            popup.addSeparator()
            popup.add(itemExit)

            val icon = TrayIcon(image, "Mihon Linux", popup).apply {
                isImageAutoSize = true
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            SwingUtilities.invokeLater {
                                if (mainFrameRef?.isVisible == true) {
                                    minimizeToTray()
                                } else {
                                    restoreFromTray()
                                }
                            }
                        }
                    }
                })
            }
            tray.add(icon)
            trayIcon = icon
        }
    }

    /**
     * Hides the main JFrame and shows the tray icon (minimizes to system tray).
     */
    fun minimizeToTray() {
        if (!isSupported) return
        mainFrameRef?.apply {
            isVisible = false
            trayIcon?.displayMessage("Mihon Linux", "Minimizado para a bandeja do sistema", TrayIcon.MessageType.INFO)
        }
    }

    /**
     * Shows the main JFrame and hides the tray icon (restores from system tray).
     */
    fun restoreFromTray() {
        if (!isSupported) return
        mainFrameRef?.apply {
            isVisible = true
            extendedState = JFrame.NORMAL
            toFront()
            requestFocus()
        }
    }

    /**
     * Dispatches a native Linux desktop notification using `notify-send` (Freedesktop standard)
     * with fallback to AWT [TrayIcon.displayMessage].
     */
    fun notify(title: String, message: String) {
        if (!desktopNotificationsEnabled) return
        appScope.launch {
            val sentNative = runCatching {
                val proc = ProcessBuilder("notify-send", "-a", "Mihon Linux", title, message)
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
            }.getOrDefault(false)

            if (!sentNative) {
                SwingUtilities.invokeLater {
                    runCatching {
                        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
                    }
                }
            }
        }
    }

    /**
     * Notifica que um capítulo foi baixado com sucesso.
     */
    fun notifyChapterDownloaded(mangaTitle: String, chapterName: String) {
        notify("Capítulo baixado", "$mangaTitle — $chapterName")
    }

    /**
     * Notifica que novos capítulos estão disponíveis.
     */
    fun notifyNewChaptersAvailable(count: Int, mangaTitles: List<String>) {
        val summary = mangaTitles.take(3).joinToString(", ") +
            if (mangaTitles.size > 3) " e mais ${mangaTitles.size - 3}" else ""
        notify("Novos capítulos disponíveis ($count)", summary)
    }

    /**
     * Notifica erro de download.
     */
    fun notifyDownloadError(mangaTitle: String, chapterName: String, error: String) {
        notify("Erro de download", "$mangaTitle — $chapterName\n$error")
    }

    /**
     * Notifica que a sincronização do tracker foi concluída.
     */
    fun notifyTrackerSyncCompleted(syncedCount: Int) {
        notify("Sync tracker concluído", "$syncedCount capítulo(s) sincronizado(s) com AniList/MyAnimeList")
    }
}