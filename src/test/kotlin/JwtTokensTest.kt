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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtTokensTest : FunSpec({
    test("access and refresh JWTs carry signed purpose and CSRF claims with separate lifetimes") {
        val fixture = TokenFixture()
        val pair = fixture.tokens.startLogin()
        val csrf = JWT.decode(pair.accessToken).getClaim("csrf").asString()
        csrf shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
        JWT.decode(fixture.tokens.startLogin().accessToken).getClaim("csrf").asString() shouldNotBe csrf

        for ((value, purpose, lifetime, type) in listOf(
            listOf(pair.accessToken, "access", "300", "at+jwt"),
            listOf(pair.refreshToken, "refresh", "600", "refresh+jwt"),
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
            jwt.getClaim("auth_time").asLong() shouldBe fixture.clock.instant().epochSecond
            jwt.getClaim("absolute_exp").asLong() shouldBe fixture.clock.instant().plusSeconds(86_400).epochSecond
            jwt.expiresAtAsInstant.epochSecond - jwt.issuedAtAsInstant.epochSecond shouldBe lifetime.toLong()
            jwt.claims.keys.intersect(setOf("password", "passwordHash", "jwtSecret", "ip", "lastActivityAt")).isEmpty() shouldBe true
            String(java.util.Base64.getUrlDecoder().decode(jwt.payload), Charsets.UTF_8)
                .shouldNotContain(TOKEN_PASSWORD)
        }
    }

    test("verification needs only the signing settings and not the issuing service instance") {
        val fixture = TokenFixture()
        val pair = fixture.tokens.startLogin()
        val access = pair.accessToken
        val refresh = pair.refreshToken
        val fresh = JwtTokens(fixture.settings, fixture.clock)

        fresh.verifyAccess(access).shouldNotBeNull()
        fresh.verifyRefresh(refresh).shouldNotBeNull()
        fresh.verifyAccess(refresh).shouldBeNull()
        fresh.verifyRefresh(access).shouldBeNull()
    }

    test("JWTs expire at the exact access and refresh deadlines without grace time") {
        val fixture = TokenFixture()
        val pair = fixture.tokens.startLogin()
        val access = pair.accessToken
        val refresh = pair.refreshToken

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
        val pair = fixture.tokens.startLogin()
        val access = JWT.decode(pair.accessToken)
        val refresh = JWT.decode(pair.refreshToken)

        access.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(12)
        refresh.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(30)
        fixture.tokens.verifyAccess(fixture.signed { withExpiresAt(fixture.clock.instant().plusSeconds(13)) }).shouldBeNull()
        fixture.tokens.verifyRefresh(fixture.signed(refresh = true) {
            withExpiresAt(fixture.clock.instant().plusSeconds(31))
        }).shouldBeNull()
    }

    test("renewal replaces both JWTs and extends their lifetime while preserving the login deadline") {
        val fixture = TokenFixture()
        val original = fixture.tokens.startLogin()
        val refresh = fixture.tokens.verifyRefresh(original.refreshToken).shouldNotBeNull()
        fixture.clock.advanceMillis(599_000)

        val renewed = fixture.tokens.renew(refresh).shouldNotBeNull()
        val access = JWT.decode(renewed.accessToken)
        val nextRefresh = JWT.decode(renewed.refreshToken)

        renewed.accessToken shouldNotBe original.accessToken
        renewed.refreshToken shouldNotBe original.refreshToken
        access.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(300)
        nextRefresh.expiresAtAsInstant shouldBe fixture.clock.instant().plusSeconds(600)
        for (token in listOf(access, nextRefresh)) {
            token.issuedAtAsInstant shouldBe fixture.clock.instant()
            token.getClaim("csrf").asString() shouldBe refresh.getClaim("csrf").asString()
            for (claim in listOf("auth_time", "absolute_exp")) {
                token.getClaim(claim).asLong() shouldBe refresh.getClaim(claim).asLong()
            }
            token.id shouldNotBe refresh.id
        }
        access.id shouldNotBe nextRefresh.id
        fixture.clock.advanceMillis(1_000)
        fixture.tokens.verifyRefresh(original.refreshToken).shouldBeNull()
        fixture.tokens.verifyAccess(renewed.accessToken).shouldNotBeNull()
        fixture.tokens.verifyRefresh(renewed.refreshToken).shouldNotBeNull()
    }

    test("repeated renewal shortens final tokens and never extends the twenty four hour login deadline") {
        val fixture = TokenFixture()
        var pair = fixture.tokens.startLogin()
        val original = JWT.decode(pair.refreshToken)
        repeat(144) {
            fixture.clock.advanceMillis(599_000)
            val refresh = fixture.tokens.verifyRefresh(pair.refreshToken).shouldNotBeNull()
            pair = fixture.tokens.renew(refresh).shouldNotBeNull()
        }
        val finalAccess = fixture.tokens.verifyAccess(pair.accessToken).shouldNotBeNull()
        val finalRefresh = fixture.tokens.verifyRefresh(pair.refreshToken).shouldNotBeNull()

        for (token in listOf(finalAccess, finalRefresh)) {
            token.getClaim("auth_time").asLong() shouldBe original.getClaim("auth_time").asLong()
            token.getClaim("absolute_exp").asLong() shouldBe original.getClaim("absolute_exp").asLong()
            token.expiresAtAsInstant.epochSecond shouldBe original.getClaim("absolute_exp").asLong()
            fixture.tokens.secondsRemaining(token) shouldBe 144L
        }
        fixture.clock.advanceMillis(144_000)
        fixture.tokens.verifyAccess(pair.accessToken).shouldBeNull()
        fixture.tokens.verifyRefresh(pair.refreshToken).shouldBeNull()
        fixture.tokens.renew(finalRefresh).shouldBeNull()
    }

    test("a previously verified refresh cannot renew after it expires") {
        val fixture = TokenFixture()
        val pair = fixture.tokens.startLogin()
        val refresh = fixture.tokens.verifyRefresh(pair.refreshToken).shouldNotBeNull()
        fixture.clock.advanceMillis(600_000)

        fixture.tokens.renew(refresh).shouldBeNull()
    }

    test("valid integer login claims remain usable beyond the thirty two bit epoch range") {
        val fixture = TokenFixture()
        fixture.clock.advanceMillis(450_000_000_000)
        val pair = fixture.tokens.startLogin()

        fixture.tokens.verifyAccess(pair.accessToken).shouldNotBeNull()
        fixture.tokens.verifyRefresh(pair.refreshToken).shouldNotBeNull()
        JWT.decode(pair.accessToken).getClaim("auth_time").asLong() shouldBe fixture.clock.instant().epochSecond
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
            "missing authentication time" to { withNullClaim("auth_time") },
            "authentication time string" to { fixture -> withClaim("auth_time", fixture.clock.instant().epochSecond.toString()) },
            "authentication time boolean" to { withClaim("auth_time", true) },
            "fractional authentication time" to { fixture -> withClaim("auth_time", fixture.clock.instant().epochSecond + 0.5) },
            "future authentication time" to { fixture -> withClaim("auth_time", fixture.clock.instant().plusSeconds(1).epochSecond) },
            "authentication after issue time" to { fixture ->
                withIssuedAt(fixture.clock.instant().minusSeconds(1))
                withExpiresAt(fixture.clock.instant().plusSeconds(299))
            },
            "missing absolute deadline" to { withNullClaim("absolute_exp") },
            "absolute deadline string" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().plusSeconds(86_400).epochSecond.toString()) },
            "absolute deadline boolean" to { withClaim("absolute_exp", true) },
            "fractional absolute deadline" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().plusSeconds(86_400).epochSecond + 0.5) },
            "deadline equals authentication time" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().epochSecond) },
            "deadline before authentication time" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().minusSeconds(1).epochSecond) },
            "expiry exceeds absolute deadline" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().plusSeconds(1).epochSecond) },
            "absolute lifetime exceeds policy" to { fixture -> withClaim("absolute_exp", fixture.clock.instant().plusSeconds(86_401).epochSecond) },
            "legacy token without login context" to {
                withNullClaim("auth_time")
                withNullClaim("absolute_exp")
            },
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
                if (refresh) fixture.tokens.verifyRefresh(fixture.signed(refresh = true)).shouldNotBeNull()
                else fixture.tokens.verifyAccess(fixture.signed()).shouldNotBeNull()
                val value = fixture.signed(refresh) { change(fixture) }
                if (refresh) fixture.tokens.verifyRefresh(value).shouldBeNull()
                else fixture.tokens.verifyAccess(value).shouldBeNull()
            }
        }
    }

    test("numeric login claims outside the signed integer range are rejected") {
        val fixture = TokenFixture()
        for (refresh in listOf(false, true)) {
            val baseline = fixture.signed(refresh)
            if (refresh) fixture.tokens.verifyRefresh(baseline).shouldNotBeNull()
            else fixture.tokens.verifyAccess(baseline).shouldNotBeNull()
            for (claim in listOf("auth_time", "absolute_exp")) {
                for (raw in listOf("9223372036854775808", "-9223372036854775809")) {
                    val value = fixture.signedWithRawClaim(refresh, claim, raw)
                    if (refresh) fixture.tokens.verifyRefresh(value).shouldBeNull()
                    else fixture.tokens.verifyAccess(value).shouldBeNull()
                }
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
        val pair = fixture.tokens.startLogin()
        val access = fixture.tokens.verifyAccess(pair.accessToken).shouldNotBeNull()
        val refresh = fixture.tokens.verifyRefresh(pair.refreshToken).shouldNotBeNull()
        val csrf = access.getClaim("csrf").asString()
        fixture.clock.advanceMillis(250)

        browser.metadata(null, null).shouldBeNull()
        val metadata = browser.metadata(access, refresh).shouldNotBeNull()
        metadata.username shouldBe TOKEN_USERNAME
        metadata.expiresIn shouldBe 600L
        metadata.accessExpiresIn shouldBe 300L
        metadata.csrfToken shouldBe csrf
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
            val pair = fixture.tokens.startLogin()
            for (refresh in listOf(false, true)) {
                val value = if (refresh) pair.refreshToken else pair.accessToken
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
        val access = fixture.tokens.verifyAccess(fixture.tokens.startLogin().accessToken).shouldNotBeNull()
        val csrf = access.getClaim("csrf").asString()

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
    absoluteSeconds: Long = 86_400,
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
        absoluteTokenTtlSeconds = absoluteSeconds,
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
        .withClaim("auth_time", clock.instant().epochSecond)
        .withClaim("absolute_exp", clock.instant().plusSeconds(settings.absoluteTokenTtlSeconds).epochSecond)
        .withIssuedAt(clock.instant())
        .withExpiresAt(clock.instant().plusSeconds(if (refresh) settings.refreshTokenTtlSeconds else settings.tokenTtlSeconds))
        .apply(change)
        .sign(algorithm)

    fun signedWithRawClaim(refresh: Boolean, claim: String, raw: String): String {
        val parts = signed(refresh).split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        val field = Regex("\"${Regex.escape(claim)}\":-?\\d+")
        require(field.containsMatchIn(payload))
        val altered = field.replaceFirst(payload, "\"$claim\":$raw")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(altered.toByteArray(Charsets.UTF_8))
        val content = "${parts[0]}.$encoded"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.toByteArray(Charsets.US_ASCII)))
        return "$content.$signature"
    }
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
