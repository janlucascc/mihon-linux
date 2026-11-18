package mihon.domain.track

import kotlinx.serialization.Serializable

@Serializable
data class TrackerAuth(
    val trackerType: TrackerType,
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenExpiry: Long = 0,
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "",
    val username: String = "",
    val isAuthenticated: Boolean = false,
    val lastSyncTimestamp: Long = 0,
) {
    companion object {
        fun empty(type: TrackerType): TrackerAuth = TrackerAuth(trackerType = type)
    }

    fun copyWith(
        accessToken: String? = null,
        refreshToken: String? = null,
        tokenExpiry: Long? = null,
        isAuthenticated: Boolean? = null,
        lastSyncTimestamp: Long? = null,
    ): TrackerAuth = copy(
        accessToken = accessToken ?: this.accessToken,
        refreshToken = refreshToken ?: this.refreshToken,
        tokenExpiry = tokenExpiry ?: this.tokenExpiry,
        isAuthenticated = isAuthenticated ?: this.isAuthenticated,
        lastSyncTimestamp = lastSyncTimestamp ?: this.lastSyncTimestamp,
    )

    val isTokenExpired: Boolean
        get() = tokenExpiry > 0 && System.currentTimeMillis() >= tokenExpiry
}