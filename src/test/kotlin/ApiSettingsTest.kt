package info.remzi.currencyquiz

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiSettingsTest {
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
