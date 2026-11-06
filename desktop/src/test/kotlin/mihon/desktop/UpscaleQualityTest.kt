package mihon.desktop

import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class UpscaleQualityTest {

    @Test
    fun testUpscaleQualityComparison() {
        val scratchDir = File("/home/janlucas/.gemini/antigravity/brain/a3f9d193-8e0e-4aa3-aca8-99a494f20ac1/scratch/test_upscale")
        val sampleFile = File(scratchDir, "sample_orig.jpg")
        assertTrue(sampleFile.exists(), "Sample file should exist")

        val rawBytes = sampleFile.readBytes()
        val origImg = ImageIO.read(sampleFile)
        println("=== INÍCIO DO TESTE DE QUALIDADE DE IMAGEM ===")
        println("Dimensões Originais: ${origImg.width} x ${origImg.height} (${rawBytes.size / 1024} KB)")

        // 1. Process 2x com HD_64BIT
        val t0 = System.currentTimeMillis()
        val (bytes2x, ext2x) = MangaImageEnhancer.enhanceForInstall(
            rawBytes = rawBytes,
            originalExt = "jpg",
            factor = MangaImageEnhancer.InstallUpscaleFactor.X2,
            quality = MangaImageEnhancer.QualityMode.HD_64BIT,
            outputFormat = MangaImageEnhancer.InstallOutputFormat.JPEG_HQ,
        )
        val t2x = System.currentTimeMillis() - t0
        val file2x = File(scratchDir, "upscale_2x_hd64.$ext2x").apply { writeBytes(bytes2x) }
        val img2x = ImageIO.read(file2x)
        println("Resultado 2x HD 64-bit: ${img2x.width} x ${img2x.height} (${bytes2x.size / 1024} KB) em ${t2x}ms")

        // 2. Process 3x com HD_64BIT
        val t1 = System.currentTimeMillis()
        val (bytes3x, ext3x) = MangaImageEnhancer.enhanceForInstall(
            rawBytes = rawBytes,
            originalExt = "jpg",
            factor = MangaImageEnhancer.InstallUpscaleFactor.X3,
            quality = MangaImageEnhancer.QualityMode.HD_64BIT,
            outputFormat = MangaImageEnhancer.InstallOutputFormat.JPEG_HQ,
        )
        val t3x = System.currentTimeMillis() - t1
        val file3x = File(scratchDir, "upscale_3x_hd64.$ext3x").apply { writeBytes(bytes3x) }
        val img3x = ImageIO.read(file3x)
        println("Resultado 3x HD 64-bit: ${img3x.width} x ${img3x.height} (${bytes3x.size / 1024} KB) em ${t3x}ms")

        // 3. Process 2x com Lanczos-3 ULTRA (Anime4K)
        val (bytesUltra, extUltra) = MangaImageEnhancer.enhanceForInstall(
            rawBytes = rawBytes,
            originalExt = "jpg",
            factor = MangaImageEnhancer.InstallUpscaleFactor.X2,
            quality = MangaImageEnhancer.QualityMode.ULTRA,
            outputFormat = MangaImageEnhancer.InstallOutputFormat.JPEG_HQ,
        )
        val fileUltra = File(scratchDir, "upscale_2x_ultra.$extUltra").apply { writeBytes(bytesUltra) }

        // Validações
        assertTrue(img2x.width == origImg.width * 2)
        assertTrue(img2x.height == origImg.height * 2)
        assertTrue(img3x.width == origImg.width * 3)
        assertTrue(img3x.height == origImg.height * 3)
        println("=== TESTES CONCLUÍDOS COM SUCESSO ===")
    }
}
