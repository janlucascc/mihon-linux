package mihon.desktop

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences

/**
 * Global memory budget and active RAM optimization manager.
 * Enforces real byte-level budgets across reader caches, cover caches, stream buffers, and
 * image processing pipelines, while tracking both JVM Heap and actual Linux process RSS.
 */
object MemoryManager {
    private val prefs = Preferences.userRoot().node("mihon-linux-performance")

    const val DEFAULT_RAM_LIMIT_MB = 500

    var ramLimitMb: Int
        get() = prefs.getInt("ramLimitMb", DEFAULT_RAM_LIMIT_MB)
        set(value) {
            val clamped = value.coerceIn(128, 4096)
            prefs.putInt("ramLimitMb", clamped)
            // Persist a simple plain-text file so the ~/.local/bin/mihon-linux launcher can apply -Xmx on next boot
            persistLauncherRamHint(clamped)
            notifyLimitChanged(clamped)
        }

    private fun persistLauncherRamHint(limitMb: Int) {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".mihon")
            dir.mkdirs()
            File(dir, "ram_limit_mb").writeText(limitMb.toString())
        }
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
     * Real OS resident memory (RSS) in MB on Linux via `/proc/self/statm`,
     * falling back to [usedMemoryMb] on other platforms.
     */
    val processRssMb: Long
        get() = runCatching {
            val statm = File("/proc/self/statm")
            if (!statm.canRead()) return@runCatching usedMemoryMb
            val parts = statm.readText().trim().split(' ')
            if (parts.size < 2) return@runCatching usedMemoryMb
            val rssPages = parts[1].toLongOrNull() ?: return@runCatching usedMemoryMb
            (rssPages * 4096L) / (1024L * 1024L)
        }.getOrDefault(usedMemoryMb)

    /**
     * Estimates decoded pixel buffer memory for [img] in bytes.
     */
    fun estimateImageBytes(img: BufferedImage?): Long {
        if (img == null) return 0L
        val bpp = when (img.type) {
            BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED -> 1L
            BufferedImage.TYPE_USHORT_555_RGB, BufferedImage.TYPE_USHORT_565_RGB, BufferedImage.TYPE_USHORT_GRAY -> 2L
            BufferedImage.TYPE_3BYTE_BGR -> 3L
            else -> 4L
        }
        return img.width.toLong() * img.height.toLong() * bpp
    }

    /**
     * Maximum decoded pixel bytes allowed in ReaderWindow's image cache for the active RAM limit.
     */
    fun readerImageBudgetBytes(isWebtoon: Boolean): Long {
        val mb = when {
            ramLimitMb <= 256 -> if (isWebtoon) 36L else 42L
            ramLimitMb <= 384 -> if (isWebtoon) 64L else 72L
            ramLimitMb <= 550 -> if (isWebtoon) 108L else 120L
            ramLimitMb <= 768 -> if (isWebtoon) 168L else 184L
            else -> 256L
        }
        return mb * 1024L * 1024L
    }

    /**
     * Calculates the ideal maximum number of cached decoded images for the reader
     * based on the active RAM limit (paired with [readerImageBudgetBytes]).
     */
    fun calculateReaderCacheEntries(isWebtoon: Boolean): Int {
        val limit = ramLimitMb
        return when {
            limit <= 256 -> if (isWebtoon) 2 else 4
            limit <= 384 -> if (isWebtoon) 3 else 6
            limit <= 550 -> if (isWebtoon) 5 else 10
            limit <= 768 -> if (isWebtoon) 7 else 14
            else -> if (isWebtoon) 10 else 20
        }
    }

    /**
     * Maximum compressed page ByteArrays kept in RAM by [OnlineStreamArchiveReader]
     * (all pages remain fast on disk cache).
     */
    fun streamRawCacheMaxEntries(): Int = when {
        ramLimitMb <= 256 -> 3
        ramLimitMb <= 384 -> 5
        ramLimitMb <= 550 -> 8
        ramLimitMb <= 768 -> 12
        else -> 16
    }

    /**
     * Maximum cover icons kept in RAM per LRU tier in [CoverCache].
     */
    fun coverCacheMaxEntries(): Int = when {
        ramLimitMb <= 256 -> 24
        ramLimitMb <= 384 -> 36
        ramLimitMb <= 550 -> 48
        else -> 72
    }

    /**
     * Bounded decode thread count for [ReaderWindow] so parallel decoding never spikes heap.
     */
    fun readerDecodeThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            ramLimitMb <= 256 -> 2
            ramLimitMb <= 384 -> 2
            ramLimitMb <= 550 -> cores.coerceIn(2, 3)
            else -> cores.coerceIn(3, 4)
        }
    }

    /**
     * Returns the sliding window parameters (ahead, behind) for continuous Webtoon mode
     * to keep scrolling smooth without hoarding off-screen strips in memory.
     */
    fun getWebtoonWindow(limitMb: Int = ramLimitMb): Pair<Int, Int> {
        return when {
            limitMb <= 256 -> 1 to 1 // Ultra economic: 1 ahead, 1 behind
            limitMb <= 384 -> 2 to 1
            limitMb <= 550 -> 3 to 1 // 500 MB default: 3 ahead, 1 behind
            limitMb <= 768 -> 4 to 2
            else -> 6 to 3
        }
    }

    /**
     * Actively trims caches and triggers garbage collection if used heap or OS RSS approaches budget,
     * or when explicitly forced. Never blocks the Swing EDT with synchronous GC.
     */
    @Volatile
    private var lastGcTimestampMs: Long = 0L

    fun trimMemoryIfNeeded(force: Boolean = false) {
        val limit = ramLimitMb
        val used = usedMemoryMb
        val rss = processRssMb
        if (force || used >= (limit * 0.78).toLong() || rss >= (limit * 0.92).toLong()) {
            trimCallbacks.forEach { runCatching { it() } }
            val now = System.currentTimeMillis()
            if (!force && now - lastGcTimestampMs < 2500L) return
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
