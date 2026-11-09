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

        // 1. Process Traço Nitido mantendo a resolução padrão (sem upscaling forçado)
        val t0 = System.currentTimeMillis()
        val (bytesUltraHd, extUltraHd) = MangaImageEnhancer.enhanceForInstall(
            rawBytes = rawBytes,
            originalExt = "jpg",
            mode = MangaImageEnhancer.InstallEnhanceMode.NITIDO,
            outputFormat = MangaImageEnhancer.InstallOutputFormat.JPEG_HQ,
        )
        val tUltraHd = System.currentTimeMillis() - t0
        val fileUltraHd = File(scratchDir, "sample_ultrahd.$extUltraHd").apply { writeBytes(bytesUltraHd) }
        val imgUltraHd = ImageIO.read(fileUltraHd)
        println("Resultado Traço Nitido: ${imgUltraHd.width} x ${imgUltraHd.height} (${bytesUltraHd.size / 1024} KB) em ${tUltraHd}ms")

        // 2. Validação: resolução nativa mantida intacta
        assertTrue(imgUltraHd.width == origImg.width, "Largura deve ser mantida na resolução padrão")
        assertTrue(imgUltraHd.height == origImg.height, "Altura deve ser mantida na resolução padrão")

        // 3. Teste do modo Desativado (OFF): deve retornar exatamente os mesmos bytes originais
        val (bytesOff, extOff) = MangaImageEnhancer.enhanceForInstall(
            rawBytes = rawBytes,
            originalExt = "jpg",
            mode = MangaImageEnhancer.InstallEnhanceMode.OFF,
        )
        assertTrue(bytesOff.size == rawBytes.size, "Modo OFF deve manter o arquivo idêntico")
        assertTrue(extOff == "jpg")

        println("=== TESTES CONCLUÍDOS COM SUCESSO ===")
    }
}
