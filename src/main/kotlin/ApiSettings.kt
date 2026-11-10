package info.remzi.currencyquiz

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/** Configuration is deliberately not a data class: its string representation must not expose secrets. */
class ApiSettings(
    val adminUsername: String,
    val adminPasswordHash: String,
    val jwtSecret: String,
    val jwtIssuer: String = "currency-quiz-api",
    val jwtAudience: String = "currency-quiz-admin",
    val tokenTtlSeconds: Long = 300,
    val allowedOrigins: List<String> = emptyList(),
    val questionsFile: String = "data/questions.json",
    val adminAllowedOrigins: List<String> = emptyList(),
    val authCookieSecure: Boolean = true,
    val authCookieSameSite: String = "Strict",
    val refreshTokenTtlSeconds: Long = 600,
    val absoluteTokenTtlSeconds: Long = 86400,
) {
    init {
        require(adminUsername.isNotBlank() && adminUsername.length <= 100) {
            "security.adminUsername must contain between 1 and 100 characters"
        }
        require(PasswordHash.isValid(adminPasswordHash)) {
            "security.adminPasswordHash must be a bcrypt hash with cost between 10 and 16"
        }
        require(jwtSecret.isNotBlank() && jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "security.jwtSecret must contain at least 32 UTF-8 bytes"
        }
        require(jwtIssuer.isNotBlank()) { "security.jwtIssuer must not be blank" }
        require(jwtAudience.isNotBlank()) { "security.jwtAudience must not be blank" }
        require(tokenTtlSeconds in 1..Int.MAX_VALUE.toLong()) { "security.tokenTtlSeconds must be between 1 and 2147483647" }
        require(refreshTokenTtlSeconds in tokenTtlSeconds..Int.MAX_VALUE.toLong()) {
            "security.refreshTokenTtlSeconds must be at least the access lifetime and at most 2147483647"
        }
        require(absoluteTokenTtlSeconds in refreshTokenTtlSeconds..Int.MAX_VALUE.toLong()) {
            "security.absoluteTokenTtlSeconds must be at least the refresh lifetime and at most 2147483647"
        }
        require(questionsFile.isNotBlank()) { "storage.questionsFile must not be blank" }
        allowedOrigins.forEach(::validateOrigin)
        adminAllowedOrigins.forEach { origin ->
            require(origin != "*") { "security.adminAllowedOrigins requires explicit HTTP(S) origins, never '*'" }
            validateOrigin(origin)
        }
        require(authCookieSameSite in setOf("Lax", "Strict", "None")) {
            "security.authCookieSameSite must be Lax, Strict or None"
        }
        require(authCookieSameSite != "None" || authCookieSecure) {
            "security.authCookieSameSite=None requires security.authCookieSecure=true"
        }
    }

    companion object {
        fun from(config: ApplicationConfig): ApiSettings {
            fun required(path: String): String = config.propertyOrNull(path)?.getString()
                ?.takeIf(String::isNotBlank)
                ?: error("Missing required configuration: $path")

            fun optional(path: String, default: String): String =
                config.propertyOrNull(path)?.getString() ?: default

            val tokenTtl = optional("security.tokenTtlSeconds", "300").toLongOrNull()
                ?: error("security.tokenTtlSeconds must be an integer")
            val cookieSecure = optional("security.authCookieSecure", "true").toBooleanStrictOrNull()
                ?: error("security.authCookieSecure must be true or false")

            fun origins(path: String): List<String> = optional(path, "").split(',')
                .map(String::trim).filter(String::isNotEmpty)
                .map { origin ->
                    // Validate before normalizing, so malformed wildcards such as "*/" stay invalid.
                    validateOrigin(origin)
                    origin.removeSuffix("/")
                }.distinct()

            return ApiSettings(
                adminUsername = required("security.adminUsername"),
                adminPasswordHash = required("security.adminPasswordHash"),
                jwtSecret = required("security.jwtSecret"),
                jwtIssuer = optional("security.jwtIssuer", "currency-quiz-api"),
                jwtAudience = optional("security.jwtAudience", "currency-quiz-admin"),
                tokenTtlSeconds = tokenTtl,
                allowedOrigins = origins("cors.allowedOrigins"),
                questionsFile = optional("storage.questionsFile", "data/questions.json"),
                adminAllowedOrigins = origins("security.adminAllowedOrigins"),
                authCookieSecure = cookieSecure,
                authCookieSameSite = optional("security.authCookieSameSite", "Strict"),
                refreshTokenTtlSeconds = optional("security.refreshTokenTtlSeconds", "600").toLongOrNull()
                    ?: error("security.refreshTokenTtlSeconds must be an integer"),
                absoluteTokenTtlSeconds = optional("security.absoluteTokenTtlSeconds", "86400").toLongOrNull()
                    ?: error("security.absoluteTokenTtlSeconds must be an integer"),
            )
        }

        private fun validateOrigin(origin: String) {
            if (origin == "*") return
            val uri = runCatching { URI(origin) }.getOrNull()
            require(
                uri != null && uri.scheme?.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.orEmpty() in setOf("", "/") &&
                    (uri.port == -1 || uri.port in 1..65535)
            ) {
                "cors.allowedOrigins must contain '*' or HTTP(S) origins without credentials, paths, queries, or fragments"
            }
        }
    }
}
