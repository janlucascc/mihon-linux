package mihon.core.archive

import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import javax.imageio.ImageIO

object ImageDecoder {
    fun decode(bytes: ByteArray): BufferedImage? {
        if (bytes.isEmpty()) return null
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let { return it }
        // Fallback for AVIF/JXL via ImageMagick with deadlock protection
        return runCatching {
            val proc = ProcessBuilder("magick", "-", "png:-").start()
            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                proc.inputStream.use { it.readAllBytes() }
            }
            proc.outputStream.use { it.write(bytes) }
            val outBytes = stdoutFuture.get(4, java.util.concurrent.TimeUnit.SECONDS)
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (outBytes != null && outBytes.isNotEmpty()) ImageIO.read(ByteArrayInputStream(outBytes)) else null
        }.getOrNull()
    }

    /**
     * Fast header inspection for JPEG/PNG/GIF/BMP via ImageIO and WebP (RIFF VP8/VP8L/VP8X)
     * without decoding full pixels or spawning an external process.
     */
    fun readDimensions(stream: InputStream): Dimension? {
        val header = stream.readNBytes(64)
        if (header.size < 12) return null

        // Fast-path RIFF/WEBP header parser
        if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        ) {
            return parseWebpDimensions(header)
        }

        // Standard ImageIO header reader for PNG/JPEG/GIF
        return runCatching {
            val combined = java.io.SequenceInputStream(ByteArrayInputStream(header), stream)
            ImageIO.createImageInputStream(combined).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) {
                    null
                } else {
                    val reader = readers.next()
                    try {
                        reader.setInput(input)
                        Dimension(reader.getWidth(0), reader.getHeight(0))
                    } finally {
                        reader.dispose()
                    }
                }
            }
        }.getOrNull()
    }

    private fun parseWebpDimensions(b: ByteArray): Dimension? {
        if (b.size < 30) return null
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24le(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)

        val chunk = String(b, 12, 4, Charsets.US_ASCII)
        return when (chunk) {
            "VP8 " -> {
                // Frame tag at 20..22, start code 0x9d 0x01 0x2a at 23..25, width/height at 26..29
                if (u8(23) == 0x9D && u8(24) == 0x01 && u8(25) == 0x2A) {
                    val w = u16le(26) and 0x3FFF
                    val h = u16le(28) and 0x3FFF
                    if (w > 0 && h > 0) Dimension(w, h) else null
                } else {
                    null
                }
            }
            "VP8L" -> {
                // Signature 0x2f at 20, packed 14-bit width-1 and height-1 at 21..24
                if (u8(20) == 0x2F) {
                    val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                    val w = (bits and 0x3FFF) + 1
                    val h = ((bits ushr 14) and 0x3FFF) + 1
                    if (w > 0 && h > 0) Dimension(w, h) else null
                } else {
                    null
                }
            }
            "VP8X" -> {
                // 24-bit width-1 at 24..26, 24-bit height-1 at 27..29
                val w = u24le(24) + 1
                val h = u24le(27) + 1
                if (w > 0 && h > 0) Dimension(w, h) else null
            }
            else -> null
        }
    }
}

/**
 * High-performance, stream-based reader interface for local manga archives (.cbz, .zip)
 * and image directories.
 *
 * Implements [AutoCloseable] to ensure safe resource cleanup with `.use { }`.
 */
interface ArchiveReader : AutoCloseable {

    /**
     * Display name of the archive source (e.g. file or directory name).
     */
    val sourceName: String

    /**
     * Immutable list of discovered pages, pre-sorted in natural alphanumeric order.
     */
    val pages: List<ArchivePage>

    /**
     * Total number of pages in the archive.
     */
    val pageCount: Int get() = pages.size

    /**
     * Opens an [InputStream] for reading the raw bytes of the specified [page].
     * The caller is responsible for closing the returned stream.
     *
     * @throws ArchiveClosedException or [IllegalStateException] if the reader has been closed.
     * @throws NoSuchElementException if the page is not found in the archive.
     * @throws IOException on I/O error.
     */
    @Throws(IOException::class)
    fun getPageStream(page: ArchivePage): InputStream

    /**
     * Convenience method to read all bytes of the specified [page].
     * The underlying stream is automatically closed.
     *
     * @throws ArchiveClosedException or [IllegalStateException] if the reader has been closed.
     * @throws NoSuchElementException if the page is not found in the archive.
     * @throws IOException on I/O error.
     */
    @Throws(IOException::class)
    fun getPageBytes(page: ArchivePage): ByteArray = getPageStream(page).use { it.readAllBytes() }

    /**
     * Decodes and returns the specified [page] as a [BufferedImage].
     * The underlying stream is automatically closed.
     * Returns null if no registered ImageReader can decode the image format or if image bytes are corrupt.
     *
     * @throws ArchiveClosedException or [IllegalStateException] if the reader has been closed.
     * @throws NoSuchElementException if the page is not found in the archive.
     * @throws IOException on decoding or I/O error.
     */
    @Throws(IOException::class)
    fun getPageImage(page: ArchivePage): BufferedImage? = ImageDecoder.decode(getPageBytes(page))

    /**
     * Closes underlying file handles and releases system resources.
     * Must be idempotent.
     */
    override fun close()
}
