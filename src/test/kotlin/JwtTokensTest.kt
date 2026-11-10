package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class JwtTokensTest : FunSpec({
    test("access and refresh JWTs carry signed purpose and CSRF claims with separate lifetimes") {
        val fixture = TokenFixture()
        val csrf = fixture.tokens.newCsrfToken()
        csrf shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
        fixture.tokens.newCsrfToken() shouldNotBe csrf

        for ((value, purpose, lifetime, type) in listOf(
            listOf(fixture.tokens.issueAccess(csrf), "access", "300", "at+jwt"),
            listOf(fixture.tokens.issueRefresh(csrf), "refresh", "600", "refresh+jwt"),
        )) {
            val jwt = JWT.decode(value)
            Algorithm.HMAC256(TOKEN_SECRET).verify(jwt)
            jwt.algorithm shouldBe "HS256"
            jwt.type shouldBe type
            jwt.issuer shouldBe fixture.settings.jwtIssuer
            jwt.audience shouldBe listOf(fixture.settings.jwtAudience)
            jwt.subject shouldBe TOKEN_USERNAME
            jwt.getClaim("role").asString() shouldBe "admin"
            jwt.getClaim("token_use").asString() shouldBe purpose
            jwt.getClaim("csrf").asString() shouldBe csrf
            jwt.issuedAtAsInstant shouldBe fixture.clock.instant()
            jwt.expiresAtAsInstant.epochSecond - jwt.issuedAtAsInstant.epochSecond shouldBe lifetime.toLong()
            jwt.claims.keys.intersect(setOf("password", "passwordHash", "jwtSecret", "ip", "lastActivityAt")).isEmpty() shouldBe true
            String(java.util.Base64.getUrlDecoder().decode(jwt.payload), Charsets.UTF_8)
                .shouldNotContain(TOKEN_PASSWORD)
        }
    }

    test("verification needs only the signing settings and not the issuing service instance") {
        val fixture = TokenFixture()
        val access = fixture.tokens.issueAccess()
        val refresh = fixture.tokens.issueRefresh(fixture.tokens.newCsrfToken())
        val fresh = JwtTokens(fixture.settings, fixture.clock)

        fresh.verifyAccess(access).shouldNotBeNull()
        fresh.verifyRefresh(refresh).shouldNotBeNull()
        fresh.verifyAccess(refresh).shouldBeNull()
        fresh.verifyRefresh(access).shouldBeNull()
    }

    test("JWTs expire at the exact access and refresh deadlines without grace time") {
        val fixture = TokenFixture()
        val access = fixture.tokens.issueAccess()
        val refresh = fixture.tokens.issueRefresh(fixture.tokens.newCsrfToken())

        fixture.clock.advanceMillis(299_999)
        fixture.tokens.verifyAccess(access).shouldNotBeNull()
        fixture.tokens.verifyRefresh(refresh).shouldNotBeNull()
        fixture.clock.advanceMillis(1)
        fixture.tokens.verifyAccess(access).shouldBeNull()
        fixture.tokens.verifyRefresh(refresh).shouldNotBeNull()
        fixture.clock.advanceMillis(299_999)
        fixture.tokens.verifyRefresh(refresh).shouldNotBeNull()
        fixture.clock.advanceMillis(1)
        fixture.tokens.verifyRefresh(refresh).shouldBeNull()
    }

    test("custom lifetimes apply to issuance and validation") {
        val fixture = TokenFixture(accessSeconds = 12, refreshSeconds = 30)
        val access = JWT.decode(fixture.tokens.issueAccess())
        val refresh = JWT.decode(fixture.tokens.issueRefresh(fixture.tokens.newCsrfToken()))

        access.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(12)
        refresh.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(30)
        fixture.tokens.verifyAccess(fixture.signed { withExpiresAt(fixture.clock.instant().plusSeconds(13)) }).shouldBeNull()
        fixture.tokens.verifyRefresh(fixture.signed(refresh = true) {
            withExpiresAt(fixture.clock.instant().plusSeconds(31))
        }).shouldBeNull()
    }

    test("renewed access cannot outlive the original refresh deadline") {
        val fixture = TokenFixture()
        val csrf = fixture.tokens.newCsrfToken()
        val refresh = fixture.tokens.verifyRefresh(fixture.tokens.issueRefresh(csrf)).shouldNotBeNull()
        val deadline = refresh.expiresAtAsInstant
        fixture.clock.advanceMillis(599_000)

        val access = fixture.tokens.issueAccess(csrf, deadline)

        JWT.decode(access).expiresAtAsInstant shouldBe deadline
        fixture.tokens.verifyAccess(access).shouldNotBeNull()
        fixture.clock.advanceMillis(1_000)
        fixture.tokens.verifyAccess(access).shouldBeNull()
    }

    test("an access deadline cannot extend the configured access lifetime") {
        val fixture = TokenFixture()
        val access = fixture.tokens.issueAccess(deadline = fixture.clock.instant().plusSeconds(600))

        JWT.decode(access).expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(300)
    }

    context("malformed or unauthenticated values cannot be verified") {
        withTests(mapOf(
            "absent" to null,
            "empty" to "",
            "opaque credential" to "a".repeat(43),
            "invalid base64url" to "!.!.!",
            "invalid JSON" to "e30.e30.invalid",
            "oversized value" to "a".repeat(10_000),
        )) { value ->
            val fixture = TokenFixture()
            fixture.tokens.verifyAccess(value).shouldBeNull()
            fixture.tokens.verifyRefresh(value).shouldBeNull()
        }
    }

    context("all security claims are required and checked for both token purposes") {
        val invalidClaims: Map<String, JWTCreator.Builder.(TokenFixture) -> Unit> = mapOf(
            "missing issuer" to { withNullClaim("iss") },
            "wrong issuer" to { withIssuer("untrusted-issuer") },
            "missing audience" to { withNullClaim("aud") },
            "wrong audience" to { withAudience("other-api") },
            "missing subject" to { withNullClaim("sub") },
            "wrong subject" to { withSubject("other-admin") },
            "missing role" to { withNullClaim("role") },
            "non-admin role" to { withClaim("role", "user") },
            "wrong role type" to { withClaim("role", 1) },
            "missing token purpose" to { withNullClaim("token_use") },
            "unknown token purpose" to { withClaim("token_use", "password-reset") },
            "wrong purpose type" to { withClaim("token_use", 1) },
            "missing CSRF" to { withNullClaim("csrf") },
            "wrong CSRF type" to { withClaim("csrf", 1) },
            "malformed CSRF" to { withClaim("csrf", "short") },
            "missing issue time" to { withNullClaim("iat") },
            "future issue time" to { fixture -> withIssuedAt(fixture.clock.instant().plusSeconds(1)) },
            "missing expiry" to { withNullClaim("exp") },
            "expired" to { fixture -> withExpiresAt(fixture.clock.instant()) },
            "zero lifetime" to { fixture ->
                withIssuedAt(fixture.clock.instant().minusSeconds(1))
                withExpiresAt(fixture.clock.instant().minusSeconds(1))
            },
            "future activation" to { fixture -> withNotBefore(fixture.clock.instant().plusSeconds(1)) },
            "missing explicit type" to { withHeader(mapOf("typ" to null)) },
            "wrong explicit type" to { withHeader(mapOf("typ" to "JWT")) },
        )
        withTests(invalidClaims) { change ->
            val fixture = TokenFixture()
            for (refresh in listOf(false, true)) {
                val value = fixture.signed(refresh) { change(fixture) }
                if (refresh) fixture.tokens.verifyRefresh(value).shouldBeNull()
                else fixture.tokens.verifyAccess(value).shouldBeNull()
            }
        }
    }

    test("a correct signature cannot substitute one token purpose for another") {
        val fixture = TokenFixture()

        fixture.tokens.verifyAccess(fixture.signed { withClaim("token_use", "refresh") }).shouldBeNull()
        fixture.tokens.verifyRefresh(fixture.signed(refresh = true) { withClaim("token_use", "access") }).shouldBeNull()
    }

    test("unsigned wrongly signed and wrong algorithm tokens are rejected") {
        val fixture = TokenFixture()
        for (algorithm in listOf(
            Algorithm.none(),
            Algorithm.HMAC256("other-signing-key-with-at-least-thirty-two-bytes"),
            Algorithm.HMAC512(TOKEN_SECRET),
        )) {
            fixture.tokens.verifyAccess(fixture.signed(algorithm = algorithm)).shouldBeNull()
            fixture.tokens.verifyRefresh(fixture.signed(refresh = true, algorithm = algorithm)).shouldBeNull()
        }
    }

    test("browser metadata reports remaining verified lifetimes without exposing credentials") {
        val fixture = TokenFixture()
        val browser = BrowserTokens(fixture.settings, fixture.tokens)
        val csrf = fixture.tokens.newCsrfToken()
        val access = fixture.tokens.verifyAccess(fixture.tokens.issueAccess(csrf)).shouldNotBeNull()
        val refresh = fixture.tokens.verifyRefresh(fixture.tokens.issueRefresh(csrf)).shouldNotBeNull()
        fixture.clock.advanceMillis(250)

        browser.metadata(null, null).shouldBeNull()
        val pair = browser.metadata(access, refresh).shouldNotBeNull()
        pair.username shouldBe TOKEN_USERNAME
        pair.expiresIn shouldBe 600L
        pair.accessExpiresIn shouldBe 300L
        pair.csrfToken shouldBe csrf
        browser.metadata(access, null).shouldNotBeNull().expiresIn shouldBe 300L
        browser.metadata(null, refresh).shouldNotBeNull().accessExpiresIn shouldBe 0L
        val otherAccess = fixture.tokens.verifyAccess(fixture.tokens.issueAccess()).shouldNotBeNull()
        browser.metadata(otherAccess, refresh).shouldNotBeNull().accessExpiresIn shouldBe 0L
        fixture.clock.advanceMillis(1_000)
        browser.metadata(access, refresh).shouldNotBeNull().expiresIn shouldBe 599L
    }

    context("browser cookies apply the configured transport policy to both token purposes") {
        withTests(mapOf(
            "secure Strict" to (true to "Strict"),
            "secure Lax" to (true to "Lax"),
            "secure None" to (true to "None"),
            "local HTTP Strict" to (false to "Strict"),
        )) { (secure, sameSite) ->
            val fixture = TokenFixture(secure = secure, sameSite = sameSite)
            val browser = BrowserTokens(fixture.settings, fixture.tokens)
            for (refresh in listOf(false, true)) {
                val value = if (refresh) fixture.tokens.issueRefresh(fixture.tokens.newCsrfToken())
                    else fixture.tokens.issueAccess()
                val cookie = browser.cookie(value, refresh = refresh)
                cookie.name shouldBe if (refresh) ADMIN_REFRESH_COOKIE_NAME else ADMIN_COOKIE_NAME
                cookie.path shouldBe if (refresh) ADMIN_REFRESH_COOKIE_PATH else ADMIN_COOKIE_PATH
                cookie.maxAge shouldBe if (refresh) 600 else 300
                cookie.value shouldBe value
                cookie.secure shouldBe secure
                cookie.httpOnly shouldBe true
                cookie.extensions["SameSite"] shouldBe sameSite
                cookie.domain.shouldBeNull()
                browser.cookie("", refresh = refresh, clearing = true).maxAge shouldBe 0
            }
            shouldThrow<JWTDecodeException> { browser.cookie("invalid-jwt") }
            shouldThrow<JWTDecodeException> { browser.cookie("invalid-jwt", refresh = true) }
        }
    }

    test("CSRF validation matches the signed nonce and trusts only exact admin origins") {
        val fixture = TokenFixture()
        val browser = BrowserTokens(fixture.settings, fixture.tokens)
        val csrf = fixture.tokens.newCsrfToken()
        val access = fixture.tokens.verifyAccess(fixture.tokens.issueAccess(csrf)).shouldNotBeNull()

        for (candidate in listOf(null, "", "+".repeat(43), "short", "a".repeat(43), "a".repeat(44))) {
            browser.validCsrf(access, candidate) shouldBe false
        }
        browser.validCsrf(access, csrf) shouldBe true
        for (origin in listOf(null, "null", "*", "$TOKEN_ORIGIN/", "$TOKEN_ORIGIN.attacker.example")) {
            browser.trustedOrigin(origin) shouldBe false
        }
        browser.trustedOrigin(TOKEN_ORIGIN) shouldBe true
    }
})

private class TokenFixture(
    accessSeconds: Long = 300,
    refreshSeconds: Long = 600,
    secure: Boolean = true,
    sameSite: String = "Strict",
) {
    val clock = TokenClock()
    val settings = ApiSettings(
        adminUsername = TOKEN_USERNAME,
        adminPasswordHash = testPasswordHash(TOKEN_PASSWORD),
        jwtSecret = TOKEN_SECRET,
        tokenTtlSeconds = accessSeconds,
        refreshTokenTtlSeconds = refreshSeconds,
        adminAllowedOrigins = listOf(TOKEN_ORIGIN),
        authCookieSecure = secure,
        authCookieSameSite = sameSite,
    )
    val tokens = JwtTokens(settings, clock)

    fun signed(
        refresh: Boolean = false,
        algorithm: Algorithm = Algorithm.HMAC256(TOKEN_SECRET),
        change: JWTCreator.Builder.() -> Unit = {},
    ): String = JWT.create()
        .withHeader(mapOf("typ" to if (refresh) "refresh+jwt" else "at+jwt"))
        .withIssuer(settings.jwtIssuer)
        .withAudience(settings.jwtAudience)
        .withSubject(settings.adminUsername)
        .withClaim("role", "admin")
        .withClaim("token_use", if (refresh) "refresh" else "access")
        .withClaim("csrf", "a".repeat(43))
        .withIssuedAt(clock.instant())
        .withExpiresAt(clock.instant().plusSeconds(if (refresh) settings.refreshTokenTtlSeconds else settings.tokenTtlSeconds))
        .apply(change)
        .sign(algorithm)
}

private class TokenClock : Clock() {
    private var current = Instant.parse("2026-11-02T12:00:00Z")
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advanceMillis(milliseconds: Long) { current = current.plusMillis(milliseconds) }
}

private const val TOKEN_USERNAME = "jwt-test-admin"
private const val TOKEN_PASSWORD = "jwt-test-password-123"
private const val TOKEN_SECRET = "test-signing-key-with-at-least-thirty-two-bytes"
private const val TOKEN_ORIGIN = "https://admin.example"
