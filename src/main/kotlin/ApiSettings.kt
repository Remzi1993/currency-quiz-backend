package info.remzi.currencyquiz

import io.ktor.server.config.ApplicationConfig
import java.net.URI

/** Configuration is deliberately not a data class: its string representation must not expose secrets. */
class ApiSettings(
    val adminUsername: String,
    val adminPassword: String,
    val jwtSecret: String,
    val jwtIssuer: String = "currencyquiz-api",
    val jwtAudience: String = "currencyquiz-admin",
    val tokenTtlSeconds: Long = 3600,
    val allowedOrigins: List<String> = emptyList(),
    val questionsFile: String = "data/questions.json",
) {
    init {
        require(adminUsername.isNotBlank() && adminUsername.length <= 100) {
            "security.adminUsername must contain between 1 and 100 characters"
        }
        require(adminPassword.isNotBlank() && adminPassword.length in 12..1024) {
            "security.adminPassword must contain between 12 and 1024 characters"
        }
        require(jwtSecret.isNotBlank() && jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "security.jwtSecret must contain at least 32 UTF-8 bytes"
        }
        require(jwtIssuer.isNotBlank()) { "security.jwtIssuer must not be blank" }
        require(jwtAudience.isNotBlank()) { "security.jwtAudience must not be blank" }
        require(tokenTtlSeconds in 1..86400) { "security.tokenTtlSeconds must be between 1 and 86400" }
        require(questionsFile.isNotBlank()) { "storage.questionsFile must not be blank" }
        allowedOrigins.forEach(::validateOrigin)
    }

    companion object {
        fun from(config: ApplicationConfig): ApiSettings {
            fun required(path: String): String = config.propertyOrNull(path)?.getString()
                ?.takeIf(String::isNotBlank)
                ?: error("Missing required configuration: $path")

            fun optional(path: String, default: String): String =
                config.propertyOrNull(path)?.getString() ?: default

            val tokenTtl = optional("security.tokenTtlSeconds", "3600").toLongOrNull()
                ?: error("security.tokenTtlSeconds must be an integer")

            return ApiSettings(
                adminUsername = required("security.adminUsername"),
                adminPassword = required("security.adminPassword"),
                jwtSecret = required("security.jwtSecret"),
                jwtIssuer = optional("security.jwtIssuer", "currencyquiz-api"),
                jwtAudience = optional("security.jwtAudience", "currencyquiz-admin"),
                tokenTtlSeconds = tokenTtl,
                allowedOrigins = optional("cors.allowedOrigins", "").split(',')
                    .map(String::trim).filter(String::isNotEmpty).map { it.removeSuffix("/") }.distinct(),
                questionsFile = optional("storage.questionsFile", "data/questions.json"),
            )
        }

        private fun validateOrigin(origin: String) {
            val uri = runCatching { URI(origin) }.getOrNull()
            require(
                uri != null && uri.scheme?.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.orEmpty() in setOf("", "/") &&
                    (uri.port == -1 || uri.port in 1..65535)
            ) {
                "cors.allowedOrigins must contain only HTTP(S) origins without credentials, paths, queries, or fragments"
            }
        }
    }
}
