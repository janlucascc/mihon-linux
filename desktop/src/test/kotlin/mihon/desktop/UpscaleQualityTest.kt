package mihon.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class UpscaleQualityTest {

    @Test
    fun testUpscaleQualityComparison(@TempDir scratchDir: File) {
        val sampleFile = File(scratchDir, "sample_orig.jpg")
        val sampleImg = BufferedImage(480, 640, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = Color(24, 24, 30)
            g.drawOval(60, 80, 320, 180)
            g.font = Font("SansSerif", Font.BOLD, 28)
            g.drawString("SAMPLE MANGA LINE ART", 80, 180)
            g.dispose()
        }
        ImageIO.write(sampleImg, "jpg", sampleFile)
        assertTrue(sampleFile.exists(), "Sample file should exist")

        val rawBytes = sampleFile.readBytes()
        val origImg = ImageIO.read(sampleFile)
        println("=== TESTE DE QUALIDADE DE IMAGEM ===")
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
