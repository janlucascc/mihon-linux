package mihon.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences

/**
 * Global memory budget and active RAM optimization manager.
 * Ensures that the desktop application respects user-configured memory ceilings (default: 500 MB)
 * and dynamically sizes decoding buffers, caches, and GC eviction.
 */
object MemoryManager {
    private val prefs = Preferences.userRoot().node("mihon-linux-performance")

    const val DEFAULT_RAM_LIMIT_MB = 500

    var ramLimitMb: Int
        get() = prefs.getInt("ramLimitMb", DEFAULT_RAM_LIMIT_MB)
        set(value) {
            val clamped = value.coerceIn(128, 4096)
            prefs.putInt("ramLimitMb", clamped)
            notifyLimitChanged(clamped)
        }

    private val limitListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val trimCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun addLimitListener(listener: (Int) -> Unit) {
        limitListeners.add(listener)
    }

    fun removeLimitListener(listener: (Int) -> Unit) {
        limitListeners.remove(listener)
    }

    fun registerTrimCallback(callback: () -> Unit) {
        trimCallbacks.add(callback)
    }

    fun unregisterTrimCallback(callback: () -> Unit) {
        trimCallbacks.remove(callback)
    }

    private fun notifyLimitChanged(newLimit: Int) {
        limitListeners.forEach { runCatching { it(newLimit) } }
        trimMemoryIfNeeded(force = true)
    }

    val usedMemoryMb: Long
        get() {
            val runtime = Runtime.getRuntime()
            return ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).coerceAtLeast(0)
        }

    val totalAllocatedMemoryMb: Long
        get() = (Runtime.getRuntime().totalMemory() / (1024 * 1024)).coerceAtLeast(0)

    val maxMemoryMb: Long
        get() = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).coerceAtLeast(0)

    /**
     * Calculates the ideal maximum number of cached decoded images for the reader
     * based on the active RAM limit.
     */
    fun calculateReaderCacheEntries(isWebtoon: Boolean): Int {
        val limit = ramLimitMb
        return when {
            limit <= 256 -> if (isWebtoon) 3 else 6
            limit <= 384 -> if (isWebtoon) 4 else 8
            limit <= 550 -> if (isWebtoon) 5 else 12 // 500 MB default target
            limit <= 768 -> if (isWebtoon) 8 else 18
            else -> if (isWebtoon) 12 else 24
        }
    }

    /**
     * Returns the sliding window parameters (ahead, behind) for continuous Webtoon mode
     * to keep scrolling smooth without hoarding off-screen strips in memory.
     */
    fun getWebtoonWindow(limitMb: Int = ramLimitMb): Pair<Int, Int> {
        return when {
            limitMb <= 256 -> 2 to 1 // 2 ahead, 1 behind (total 4 pages)
            limitMb <= 384 -> 2 to 1
            limitMb <= 550 -> 3 to 2 // 500 MB default: 3 ahead, 2 behind (total ~6 pages)
            limitMb <= 768 -> 5 to 3
            else -> 8 to 4
        }
    }

    /**
     * Actively trims caches and triggers garbage collection if used heap exceeds 85% of budget,
     * or when explicitly forced. Never blocks the Swing EDT with synchronous GC.
     */
    @Volatile
    private var lastGcTimestampMs: Long = 0L

    fun trimMemoryIfNeeded(force: Boolean = false) {
        val limit = ramLimitMb
        val used = usedMemoryMb
        if (force || used >= (limit * 0.85).toLong()) {
            trimCallbacks.forEach { runCatching { it() } }
            val now = System.currentTimeMillis()
            if (!force && now - lastGcTimestampMs < 3000L) return
            lastGcTimestampMs = now
            if (javax.swing.SwingUtilities.isEventDispatchThread() && !force) {
                Thread({ System.gc() }, "mihon-bg-gc").apply {
                    isDaemon = true
                    start()
                }
            } else {
                System.gc()
            }
        }
    }
}
