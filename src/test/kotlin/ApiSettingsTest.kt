package info.remzi.currencyquiz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.config.MapApplicationConfig

class ApiSettingsTest : FunSpec() {
    init {
        test("defaults use bounded lifetimes and require explicit browser origins") {
            val settings = ApiSettings.from(config())

            settings.tokenTtlSeconds shouldBe 300L
            settings.sessionIdleSeconds shouldBe 900L
            settings.sessionAbsoluteSeconds shouldBe 1800L
            settings.allowedOrigins shouldBe emptyList()
            settings.adminAllowedOrigins shouldBe emptyList()
            settings.authCookieSecure shouldBe true
            settings.authCookieSameSite shouldBe "Strict"
            settings.jwtIssuer shouldBe "currency-quiz-api"
            settings.jwtAudience shouldBe "currency-quiz-admin"
            settings.questionsFile shouldBe "data/questions.json"
        }

        context("invalid security lifetimes") {
            withTests(
                nameFn = { (key, value) -> "$key rejects $value" },
                "security.tokenTtlSeconds" to "301",
                "security.tokenTtlSeconds" to "0",
                "security.tokenTtlSeconds" to "-1",
                "security.sessionIdleSeconds" to "901",
                "security.sessionIdleSeconds" to "299",
                "security.sessionAbsoluteSeconds" to "1801",
                "security.sessionAbsoluteSeconds" to "899",
            ) { (key, value) ->
                shouldThrow<IllegalArgumentException> { ApiSettings.from(config().apply { put(key, value) }) }
            }
        }

        context("integer configuration must be parseable without overflow") {
            withTests(
                nameFn = { (key, value) -> "$key rejects '$value'" },
                listOf("security.tokenTtlSeconds", "security.sessionIdleSeconds", "security.sessionAbsoluteSeconds")
                    .flatMap { key -> listOf("", "1.5", "seconds", "9223372036854775808").map { key to it } },
            ) { (key, value) ->
                shouldThrow<IllegalStateException> {
                    ApiSettings.from(config().apply { put(key, value) })
                }.message shouldBe "$key must be an integer"
            }
        }

        test("minimum and maximum lifetime boundaries are accepted") {
            for (seconds in listOf(1L, 300L)) {
                val settings = ApiSettings.from(config().apply {
                    put("security.tokenTtlSeconds", "$seconds")
                    put("security.sessionIdleSeconds", "$seconds")
                    put("security.sessionAbsoluteSeconds", "$seconds")
                })

                settings.tokenTtlSeconds shouldBe seconds
                settings.sessionIdleSeconds shouldBe seconds
                settings.sessionAbsoluteSeconds shouldBe seconds
            }
        }

        context("required credentials cannot be missing or blank") {
            withTests(
                nameFn = { it },
                listOf("security.adminUsername", "security.adminPassword", "security.jwtSecret"),
            ) { missingKey ->
                val missing = MapApplicationConfig().apply {
                    for ((key, value) in requiredSettings) if (key != missingKey) put(key, value)
                }
                shouldThrow<IllegalStateException> { ApiSettings.from(missing) }.message shouldBe
                    "Missing required configuration: $missingKey"
                shouldThrow<IllegalStateException> {
                    ApiSettings.from(config().apply { put(missingKey, " \t") })
                }.message shouldBe "Missing required configuration: $missingKey"
            }
        }

        context("credential length boundaries") {
            withTests(
                nameFn = { (key, value) -> "$key rejects length ${value.length}" },
                "security.adminUsername" to "u".repeat(101),
                "security.adminPassword" to "p".repeat(11),
                "security.adminPassword" to "p".repeat(1025),
                "security.jwtSecret" to "s".repeat(31),
            ) { (key, value) ->
                shouldThrow<IllegalArgumentException> { ApiSettings.from(config().apply { put(key, value) }) }
            }
        }

        test("credential boundaries and UTF-8 secret byte length are accepted") {
            for ((username, password) in listOf("u" to "p".repeat(12), "u".repeat(100) to "p".repeat(1024))) {
                val settings = ApiSettings.from(config().apply {
                    put("security.adminUsername", username)
                    put("security.adminPassword", password)
                    put("security.jwtSecret", "é".repeat(16))
                })
                settings.adminUsername shouldBe username
                settings.adminPassword shouldBe password
                settings.jwtSecret.toByteArray(Charsets.UTF_8).size shouldBe 32
            }
        }

        context("blank optional settings cannot disable security or storage") {
            withTests("security.jwtIssuer", "security.jwtAudience", "storage.questionsFile") { key ->
                shouldThrow<IllegalArgumentException> { ApiSettings.from(config().apply { put(key, "  ") }) }
            }
        }

        test("settings string representation never exposes credentials or signing key") {
            val settings = ApiSettings.from(config())

            settings.toString() shouldNotContain settings.adminUsername
            settings.toString() shouldNotContain settings.adminPassword
            settings.toString() shouldNotContain settings.jwtSecret
        }

        test("local browser settings normalize trusted origins and allow explicit insecure HTTP cookies") {
            val local = ApiSettings.from(config().apply {
                put("security.adminAllowedOrigins", " http://localhost:5173/, https://quiz.example, http://localhost:5173 ")
                put("security.authCookieSecure", "false")
            })

            local.adminAllowedOrigins shouldBe listOf("http://localhost:5173", "https://quiz.example")
            local.authCookieSecure shouldBe false
        }

        context("admin origins must be explicit") {
            withTests("*", "https://quiz.example,*", "https://*.example", "https://quiz.example/path") { origins ->
                shouldThrow<IllegalArgumentException> {
                    ApiSettings.from(config().apply { put("security.adminAllowedOrigins", origins) })
                }
            }
        }

        context("cookie security requires strict boolean parsing") {
            withTests(nameFn = { "rejects '$it'" }, "yes", "1", "TRUE", "") { value ->
                shouldThrow<IllegalStateException> {
                    ApiSettings.from(config().apply { put("security.authCookieSecure", value) })
                }
            }
        }

        context("SameSite only accepts supported case-sensitive values") {
            withTests(nameFn = { "rejects '$it'" }, "lax", "invalid", "") { sameSite ->
                shouldThrow<IllegalArgumentException> {
                    ApiSettings.from(config().apply { put("security.authCookieSameSite", sameSite) })
                }
            }
        }

        test("cross-site cookies require Secure") {
            shouldThrow<IllegalArgumentException> {
                ApiSettings.from(config().apply {
                    put("security.authCookieSameSite", "None")
                    put("security.authCookieSecure", "false")
                })
            }
            ApiSettings.from(config().apply { put("security.authCookieSameSite", "None") })
                .authCookieSameSite shouldBe "None"
            ApiSettings.from(config().apply { put("security.authCookieSameSite", "Lax") })
                .authCookieSameSite shouldBe "Lax"
        }

        test("an exact wildcard is accepted and repeated entries are normalized") {
            ApiSettings.from(config(" * , * ")).allowedOrigins shouldBe listOf("*")
            settings(listOf("*")).allowedOrigins shouldBe listOf("*")
        }

        test("explicit public origins retain trimming trailing slash normalization and deduplication") {
            ApiSettings.from(config(" https://quiz.example/, http://localhost:5173/, https://quiz.example "))
                .allowedOrigins shouldBe listOf("https://quiz.example", "http://localhost:5173")
            ApiSettings.from(config("https://quiz.example/, *")).allowedOrigins shouldBe listOf("https://quiz.example", "*")
            ApiSettings.from(config(" , ")).allowedOrigins shouldBe emptyList()
        }

        context("wildcards do not bypass malformed origin validation") {
            withTests(
                "*/", "*.example.com", "https://*.example.com", "https://example.com/path",
                "https://example.com?query=true", "https://example.com#fragment",
                "https://user:password@example.com", "not-an-origin", "ftp://example.com",
                "https://example.com:0", "https://example.com:65536", "https://",
            ) { origin ->
                shouldThrow<IllegalArgumentException> { settings(listOf(origin)) }
                shouldThrow<IllegalArgumentException> { ApiSettings.from(config(origin)) }
                shouldThrow<IllegalArgumentException> { ApiSettings.from(config("*,$origin")) }
            }
        }
    }

    private val requiredSettings = listOf(
        "security.adminUsername" to "test-admin",
        "security.adminPassword" to "test-admin-password-123",
        "security.jwtSecret" to "test-signing-key-with-at-least-thirty-two-bytes",
    )

    private fun config(origins: String? = null) = MapApplicationConfig(*requiredSettings.toTypedArray()).apply {
        if (origins != null) put("cors.allowedOrigins", origins)
    }

    private fun settings(origins: List<String>) = ApiSettings(
        adminUsername = "test-admin",
        adminPassword = "test-admin-password-123",
        jwtSecret = "test-signing-key-with-at-least-thirty-two-bytes",
        allowedOrigins = origins,
    )
}
