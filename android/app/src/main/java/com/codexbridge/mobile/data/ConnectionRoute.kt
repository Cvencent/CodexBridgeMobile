package com.codexbridge.mobile.data

enum class ConnectionRole {
    Lan,
    Wan,
}

data class RouteEndpoint(
    val url: String,
    val role: ConnectionRole,
)

data class StoredConnectionRoutes(
    val lanUrl: String,
    val wanUrl: String,
    val legacyUrl: String,
) {
    val primary: String
        get() = lanUrl.ifBlank { wanUrl.ifBlank { legacyUrl } }

    val hasUsableRoute: Boolean
        get() = primary.isNotBlank()

    val hasDualRoutes: Boolean
        get() = lanUrl.isNotBlank() && wanUrl.isNotBlank() && lanUrl != wanUrl
}

object ConnectionRoute {
    fun fromStored(
        legacyUrl: String,
        lanUrl: String,
        wanUrl: String,
    ): StoredConnectionRoutes {
        val legacy = normalizeOptional(legacyUrl)
        return StoredConnectionRoutes(
            lanUrl = normalizeOptional(lanUrl) ?: "",
            wanUrl = normalizeOptional(wanUrl) ?: "",
            legacyUrl = legacy ?: "",
        )
    }

    fun candidates(
        lanUrl: String,
        wanUrl: String,
        onWifi: Boolean,
    ): List<RouteEndpoint> {
        val lan = normalizeOptional(lanUrl)?.let { RouteEndpoint(it, ConnectionRole.Lan) }
        val wan = normalizeOptional(wanUrl)?.let { RouteEndpoint(it, ConnectionRole.Wan) }
        val ordered = if (onWifi) listOfNotNull(lan, wan) else listOfNotNull(wan, lan)
        return ordered.distinctBy { it.url }
    }

    fun candidatesFor(
        legacyUrl: String,
        lanUrl: String,
        wanUrl: String,
        onWifi: Boolean,
    ): List<RouteEndpoint> {
        val stored = fromStored(legacyUrl, lanUrl, wanUrl)
        if (stored.lanUrl.isBlank() && stored.wanUrl.isBlank()) {
            return stored.legacyUrl.takeIf { it.isNotBlank() }
                ?.let { listOf(RouteEndpoint(it, ConnectionRole.Lan)) }
                .orEmpty()
        }
        return candidates(stored.lanUrl, stored.wanUrl, onWifi)
    }

    /**
     * The bridge reports its local listener port, which is not necessarily the
     * port exposed by a reverse proxy. Pairing must keep the address the user
     * entered so a WAN route such as :8080 is not rewritten to :8568.
     */
    fun endpointForConfiguredRoute(enteredUrl: String): String =
        ApiClient.normalizeBase(enteredUrl)

    private fun normalizeOptional(value: String): String? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching { ApiClient.normalizeBase(text) }.getOrNull()
    }
}

data class SelectedConnection(
    val url: String,
    val endpoint: RouteEndpoint,
) {
    val role: ConnectionRole
        get() = endpoint.role
}

object ConnectionSelector {
    suspend fun select(
        candidates: List<RouteEndpoint>,
        probe: suspend (RouteEndpoint) -> PingResponse?,
    ): SelectedConnection? {
        for (candidate in candidates) {
            val response = runCatching { probe(candidate) }.getOrNull() ?: continue
            if (response.ok && response.service == "codexbridge-companion") {
                return SelectedConnection(candidate.url, candidate)
            }
        }
        return null
    }
}
