package mihon.desktop.e2e

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.desktop.translator.NormalizedRect
import mihon.desktop.translator.TextOrientation
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class ManifestBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun toNormalizedRect(): NormalizedRect = NormalizedRect(x, y, width, height)
}

@Serializable
data class ManifestBlock(
    val bounds: ManifestBounds,
    val orientation: String,
    val sourceText: String,
    val expectedPtBr: String,
) {
    fun textOrientation(): TextOrientation =
        runCatching { TextOrientation.valueOf(orientation.uppercase()) }.getOrDefault(TextOrientation.HORIZONTAL)
}

@Serializable
data class ManifestPage(
    val pageIndex: Int,
    val entryName: String,
    val width: Int,
    val height: Int,
    val blocks: List<ManifestBlock>,
)

@Serializable
data class ManifestFixture(
    val file: String,
    val type: String,
    val language: String,
    val pages: List<ManifestPage>,
)

@Serializable
data class FixturesManifest(
    val fixtures: List<ManifestFixture>,
)

object E2eTestHarness {

    private val json = Json { ignoreUnknownKeys = true }

    val manifest: FixturesManifest by lazy {
        val text = resourceFile("fixtures_manifest.json").readText()
        json.decodeFromString<FixturesManifest>(text)
    }

    fun fixtureMeta(fileName: String): ManifestFixture =
        manifest.fixtures.first { it.file == fileName }

    fun resourceFile(fileName: String): File {
        val url = E2eTestHarness::class.java.classLoader.getResource("e2e/$fileName")
        if (url != null) {
            return File(url.toURI())
        }
        val fallback = File("/home/janlucas/mihon-linux/desktop/src/test/resources/e2e/$fileName")
        check(fallback.exists()) { "E2E fixture not found: $fileName" }
        return fallback
    }

    fun loadPng(fileName: String): BufferedImage =
        ImageIO.read(resourceFile(fileName)) ?: error("Failed to decode PNG fixture $fileName")

    fun assertImagesEqualBitForBit(expected: BufferedImage, actual: BufferedImage, message: String = "") {
        assertEquals(expected.width, actual.width, "$message: width mismatch")
        assertEquals(expected.height, actual.height, "$message: height mismatch")
        val w = expected.width
        val h = expected.height
        val p1 = expected.getRGB(0, 0, w, h, null, 0, w)
        val p2 = actual.getRGB(0, 0, w, h, null, 0, w)
        assertTrue(p1.contentEquals(p2), "$message: images are not identical bit-for-bit")
    }

    fun countDifferingPixelsInRect(imgA: BufferedImage, imgB: BufferedImage, rect: Rectangle): Int {
        val clamped = rect.intersection(Rectangle(0, 0, minOf(imgA.width, imgB.width), minOf(imgA.height, imgB.height)))
        if (clamped.isEmpty) return 0
        var diff = 0
        for (y in clamped.y until clamped.y + clamped.height) {
            for (x in clamped.x until clamped.x + clamped.width) {
                if (imgA.getRGB(x, y) != imgB.getRGB(x, y)) {
                    diff++
                }
            }
        }
        return diff
    }

    fun countDifferingPixelsOutsideRects(imgA: BufferedImage, imgB: BufferedImage, rects: List<Rectangle>): Int {
        val w = minOf(imgA.width, imgB.width)
        val h = minOf(imgA.height, imgB.height)
        var diff = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val insideAny = rects.any { it.contains(x, y) }
                if (!insideAny && imgA.getRGB(x, y) != imgB.getRGB(x, y)) {
                    diff++
                }
            }
        }
        return diff
    }
}
