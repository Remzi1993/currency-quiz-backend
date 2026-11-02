package info.remzi.currencyquiz

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiSettingsTest {
    @Test
    fun `security lifetimes cannot exceed five minutes access fifteen idle or thirty overall`() {
        val defaults = ApiSettings.from(config())
        assertEquals(300L, defaults.tokenTtlSeconds)
        assertEquals(900L, defaults.sessionIdleSeconds)
        assertEquals(1800L, defaults.sessionAbsoluteSeconds)
        for ((key, value) in listOf(
            "security.tokenTtlSeconds" to "301", "security.tokenTtlSeconds" to "0",
            "security.sessionIdleSeconds" to "901", "security.sessionIdleSeconds" to "299",
            "security.sessionAbsoluteSeconds" to "1801", "security.sessionAbsoluteSeconds" to "899",
        )) assertFailsWith<IllegalArgumentException> { ApiSettings.from(config().apply { put(key, value) }) }
    }
    @Test
    fun `browser sessions require explicit origins and secure cookies by default`() {
        val defaults = ApiSettings.from(config())
        assertEquals(emptyList(), defaults.adminAllowedOrigins)
        assertTrue(defaults.authCookieSecure)
        assertEquals("Strict", defaults.authCookieSameSite)
        val local = ApiSettings.from(config().apply {
            put("security.adminAllowedOrigins", " http://localhost:5173/, https://quiz.example, http://localhost:5173 ")
            put("security.authCookieSecure", "false")
        })
        assertEquals(listOf("http://localhost:5173", "https://quiz.example"), local.adminAllowedOrigins)
        assertFalse(local.authCookieSecure)
        for (origins in listOf("*", "https://quiz.example,*", "https://*.example", "https://quiz.example/path")) {
            assertFailsWith<IllegalArgumentException> { ApiSettings.from(config().apply { put("security.adminAllowedOrigins", origins) }) }
        }
    }

    @Test
    fun `cookie settings reject ambiguous booleans unsupported SameSite and insecure None`() {
        for (value in listOf("yes", "1", "TRUE", "")) {
            assertFailsWith<IllegalStateException> { ApiSettings.from(config().apply { put("security.authCookieSecure", value) }) }
        }
        for (sameSite in listOf("lax", "invalid", "")) {
            assertFailsWith<IllegalArgumentException> { ApiSettings.from(config().apply { put("security.authCookieSameSite", sameSite) }) }
        }
        assertFailsWith<IllegalArgumentException> {
            ApiSettings.from(config().apply {
                put("security.authCookieSameSite", "None")
                put("security.authCookieSecure", "false")
            })
        }
        assertEquals("None", ApiSettings.from(config().apply { put("security.authCookieSameSite", "None") }).authCookieSameSite)
    }

    @Test
    fun `an exact wildcard is accepted and repeated entries are normalized`() {
        assertEquals(listOf("*"), ApiSettings.from(config(" * , * ")).allowedOrigins)
        assertEquals(listOf("*"), settings(listOf("*")).allowedOrigins)
    }

    @Test
    fun `explicit origins retain trimming trailing slash normalization and deduplication`() {
        assertEquals(
            listOf("https://quiz.example", "http://localhost:5173"),
            ApiSettings.from(config(" https://quiz.example/, http://localhost:5173/, https://quiz.example ")).allowedOrigins,
        )
        assertEquals(
            listOf("https://quiz.example", "*"),
            ApiSettings.from(config("https://quiz.example/, *")).allowedOrigins,
        )
    }

    @Test
    fun `missing or blank origin settings do not silently enable public browser access`() {
        assertEquals(emptyList(), ApiSettings.from(config()).allowedOrigins)
        assertEquals(emptyList(), ApiSettings.from(config(" , ")).allowedOrigins)
    }

    @Test
    fun `wildcards do not bypass validation of malformed origins`() {
        for (origin in listOf(
            "*/", "*.example.com", "https://*.example.com", "https://example.com/path",
            "https://example.com?query=true", "https://example.com#fragment",
            "https://user:password@example.com", "not-an-origin",
        )) {
            assertFailsWith<IllegalArgumentException>(origin) { settings(listOf(origin)) }
            assertFailsWith<IllegalArgumentException>(origin) { ApiSettings.from(config(origin)) }
            assertFailsWith<IllegalArgumentException>(origin) { ApiSettings.from(config("*,$origin")) }
        }
    }

    private fun config(origins: String? = null) = MapApplicationConfig(
        "security.adminUsername" to "test-admin",
        "security.adminPassword" to "test-admin-password-123",
        "security.jwtSecret" to "test-signing-key-with-at-least-thirty-two-bytes",
    ).apply {
        if (origins != null) put("cors.allowedOrigins", origins)
    }

    private fun settings(origins: List<String>) = ApiSettings(
        adminUsername = "test-admin",
        adminPassword = "test-admin-password-123",
        jwtSecret = "test-signing-key-with-at-least-thirty-two-bytes",
        allowedOrigins = origins,
    )
}
