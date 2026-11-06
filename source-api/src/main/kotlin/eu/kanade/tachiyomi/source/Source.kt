package eu.kanade.tachiyomi.source

abstract class Source {
    abstract val name: String
    abstract val baseUrl: String
    abstract val language: String
    abstract val id: Long
    abstract val isNsfw: Boolean
    abstract val supportsLatest: Boolean
}
