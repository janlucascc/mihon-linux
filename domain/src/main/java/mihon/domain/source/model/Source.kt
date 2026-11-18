package mihon.domain.source.model

import kotlinx.serialization.Serializable

@Serializable
data class Source(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val type: SourceType = SourceType.JSON_SCRAPER,
    val configJson: String? = null,
    val version: String = "1.0.0",
    val enabled: Boolean = true,
    val lastSync: Long = 0,
    val errorCount: Int = 0,
    val lastError: String? = null,
) {
    enum class SourceType {
        BUILTIN, JAR, JSON_SCRAPER
    }
}