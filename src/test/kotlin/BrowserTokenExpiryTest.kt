package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BrowserTokenExpiryTest : FunSpec({
    context("JWT issuance uses one clock snapshot across a second boundary") {
        withTests(mapOf("access" to false, "refresh" to true)) { refresh ->
            val fixture = ExpiryFixture(accessSeconds = 1, refreshSeconds = 1)
            fixture.clock.advanceAfterNextRead(EXPIRY_START.plusSeconds(1))

            val pair = fixture.tokens.startLogin()
            val value = if (refresh) pair.refreshToken else pair.accessToken
            val decoded = JWT.decode(value)

            decoded.issuedAtAsInstant shouldBe EXPIRY_START
            decoded.expiresAtAsInstant shouldBe EXPIRY_START.plusSeconds(1)
            decoded.expiresAtAsInstant.epochSecond - decoded.issuedAtAsInstant.epochSecond shouldBe 1L
            JWT.decode(pair.accessToken).issuedAtAsInstant shouldBe JWT.decode(pair.refreshToken).issuedAtAsInstant
            decoded.getClaim("auth_time").asLong() shouldBe EXPIRY_START.epochSecond
            decoded.getClaim("absolute_exp").asLong() shouldBe EXPIRY_START.plusSeconds(86_400).epochSecond
        }
    }

    test("bearer JWT issuance uses one clock snapshot") {
        val fixture = ExpiryFixture(accessSeconds = 1, refreshSeconds = 1)
        fixture.clock.advanceAfterNextRead(EXPIRY_START.plusSeconds(1))

        val token = JWT.decode(fixture.tokens.issueAccess())

        token.issuedAtAsInstant shouldBe EXPIRY_START
        token.expiresAtAsInstant shouldBe EXPIRY_START.plusSeconds(1)
        token.getClaim("auth_time").asLong() shouldBe EXPIRY_START.epochSecond
    }

    test("renewed token pairs use one clock snapshot and preserve their original login context") {
        val fixture = ExpiryFixture()
        val original = fixture.tokens.startLogin()
        val verified = fixture.tokens.verifyRefresh(original.refreshToken).shouldNotBeNull()
        fixture.clock.advanceTo(EXPIRY_START.plusSeconds(300))
        fixture.clock.advanceAfterNextRead(EXPIRY_START.plusSeconds(301))

        val renewed = fixture.tokens.renew(verified).shouldNotBeNull()

        for ((value, lifetime) in listOf(renewed.accessToken to 300L, renewed.refreshToken to 600L)) {
            val token = JWT.decode(value)
            token.issuedAtAsInstant shouldBe EXPIRY_START.plusSeconds(300)
            token.expiresAtAsInstant shouldBe EXPIRY_START.plusSeconds(300 + lifetime)
            token.getClaim("auth_time").asLong() shouldBe EXPIRY_START.epochSecond
            token.getClaim("absolute_exp").asLong() shouldBe EXPIRY_START.plusSeconds(86_400).epochSecond
        }
    }

    context("credentials can expire after verification while a response is prepared") {
        withTests(mapOf("access" to false, "refresh" to true)) { refresh ->
            val fixture = ExpiryFixture()
            val pair = fixture.tokens.startLogin()
            val value = if (refresh) pair.refreshToken else pair.accessToken
            val verified = (if (refresh) fixture.tokens.verifyRefresh(value) else fixture.tokens.verifyAccess(value)).shouldNotBeNull()
            fixture.clock.advanceTo(verified.expiresAtAsInstant)

            fixture.browser.metadata(if (refresh) null else verified, if (refresh) verified else null).shouldBeNull()
        }
    }

    context("outbound cookies clear an internally issued credential that expires during response preparation") {
        withTests(mapOf("access" to false, "refresh" to true)) { refresh ->
            val fixture = ExpiryFixture()
            val pair = fixture.tokens.startLogin()
            val value = if (refresh) pair.refreshToken else pair.accessToken
            fixture.clock.advanceTo(fixture.clock.instant().plusSeconds(if (refresh) 600 else 300))

            val cookie = fixture.browser.cookie(value, refresh = refresh)

            cookie.value shouldBe value
            cookie.maxAge shouldBe 0
        }
    }

    test("refresh expiring during request handling returns unauthorized and clears cookies") {
        val fixture = ExpiryFixture()
        val directory = Files.createTempDirectory("currencyquiz-expiry-race-test-")
        try {
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, "[]")
            testApplication {
                environment { config = MapApplicationConfig() }
                application { quizModule(fixture.settings, FileQuestionRepository(storage), fixture.tokens) }
                val login = client.post("/api/v1/auth/session") {
                    header(HttpHeaders.Origin, EXPIRY_ORIGIN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"expiry-admin","password":"expiry-admin-password-123"}""")
                }
                login.status shouldBe HttpStatusCode.OK
                val csrf = Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
                val refresh = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                    .single { it.startsWith("$ADMIN_REFRESH_COOKIE_NAME=") }.substringBefore(';')
                // The verifier sees the final valid second; subsequent work sees the exact expiry boundary.
                fixture.clock.advanceTo(EXPIRY_START.plusSeconds(599))
                fixture.clock.advanceAfterNextRead(EXPIRY_START.plusSeconds(600))

                val response = client.post("/api/v1/auth/session/refresh") {
                    header(HttpHeaders.Origin, EXPIRY_ORIGIN)
                    header(HttpHeaders.Cookie, refresh)
                    header(CSRF_HEADER, csrf)
                }

                response.status shouldBe HttpStatusCode.Unauthorized
                val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                cookies.size shouldBe 2
                cookies.forEach { it.lowercase().contains("max-age=0") shouldBe true }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})

private class ExpiryFixture(accessSeconds: Long = 300, refreshSeconds: Long = 600) {
    val clock = ExpiryClock()
    val settings = ApiSettings(
        adminUsername = "expiry-admin",
        adminPasswordHash = testPasswordHash("expiry-admin-password-123"),
        jwtSecret = "expiry-test-signing-key-with-at-least-thirty-two-bytes",
        tokenTtlSeconds = accessSeconds,
        refreshTokenTtlSeconds = refreshSeconds,
        adminAllowedOrigins = listOf(EXPIRY_ORIGIN),
    )
    val tokens = JwtTokens(settings, clock)
    val browser = BrowserTokens(settings, tokens)
}

private class ExpiryClock : Clock() {
    private var current = EXPIRY_START
    private var afterRead: Instant? = null
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current.also {
        afterRead?.let { next -> current = next; afterRead = null }
    }
    fun advanceTo(instant: Instant) { current = instant }
    fun advanceAfterNextRead(instant: Instant) { afterRead = instant }
}

private const val EXPIRY_ORIGIN = "https://admin.example"
private val EXPIRY_START = Instant.parse("2026-11-09T12:00:00Z")
