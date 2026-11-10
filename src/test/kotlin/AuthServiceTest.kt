package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.SignatureVerificationException
import info.remzi.currencyquiz.contract.TokenResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

class AuthServiceTest : FunSpec({
    context("credentials must match exactly") {
        withTests(
            mapOf(
                "valid credentials" to CredentialCase(AUTH_USERNAME, AUTH_PASSWORD, true),
                "unknown username" to CredentialCase("unknown-admin", AUTH_PASSWORD, false),
                "incorrect password" to CredentialCase(AUTH_USERNAME, "incorrect-password", false),
                "both credentials incorrect" to CredentialCase("unknown-admin", "incorrect-password", false),
                "empty username" to CredentialCase("", AUTH_PASSWORD, false),
                "empty password" to CredentialCase(AUTH_USERNAME, "", false),
                "username case differs" to CredentialCase(AUTH_USERNAME.uppercase(), AUTH_PASSWORD, false),
                "password case differs" to CredentialCase(AUTH_USERNAME, AUTH_PASSWORD.uppercase(), false),
                "username has surrounding whitespace" to CredentialCase(" $AUTH_USERNAME ", AUTH_PASSWORD, false),
                "password has surrounding whitespace" to CredentialCase(AUTH_USERNAME, " $AUTH_PASSWORD ", false),
            ),
        ) { (username, password, valid) ->
            val auth = AuthService(authSettings())

            auth.authenticates(username, password) shouldBe valid
            if (valid) auth.login(username, password).shouldNotBeNull()
            else auth.login(username, password).shouldBeNull()
        }
    }

    context("issued tokens honor the configured lifetime boundaries") {
        withTests(mapOf("one second" to 1L, "five minutes" to 300L, "custom fifteen minutes" to 900L)) { lifetime ->
            val settings = authSettings(lifetime)
            val before = Instant.now().epochSecond
            val response = AuthService(settings).login(AUTH_USERNAME, AUTH_PASSWORD).shouldNotBeNull()
            val after = Instant.now().epochSecond
            val decoded = JWT.decode(response.accessToken)

            // Verify the signature directly so the one-second boundary never depends on execution speed.
            Algorithm.HMAC256(settings.jwtSecret).verify(decoded)
            decoded.algorithm shouldBe "HS256"
            decoded.type shouldBe "at+jwt"
            decoded.issuer shouldBe settings.jwtIssuer
            decoded.audience.shouldContainExactly(settings.jwtAudience)
            decoded.subject shouldBe AUTH_USERNAME
            decoded.getClaim("role").asString() shouldBe "admin"
            decoded.getClaim("token_use").asString() shouldBe "access"
            decoded.issuedAtAsInstant.epochSecond.let { it in before..after } shouldBe true
            decoded.getClaim("auth_time").asLong() shouldBe decoded.issuedAtAsInstant.epochSecond
            decoded.getClaim("absolute_exp").asLong() - decoded.getClaim("auth_time").asLong() shouldBe settings.absoluteTokenTtlSeconds
            decoded.expiresAtAsInstant.epochSecond - decoded.issuedAtAsInstant.epochSecond shouldBe lifetime
            response.expiresIn shouldBe lifetime
            response.tokenType shouldBe TokenResponse.TokenType.Bearer
            decoded.claims.keys.intersect(setOf("password", "jwtSecret", "secret")).isEmpty() shouldBe true
            decoded.payload.let { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                .shouldNotContain(AUTH_PASSWORD)
        }
    }

    test("a different signing key cannot verify an issued token") {
        val response = AuthService(authSettings()).login(AUTH_USERNAME, AUTH_PASSWORD).shouldNotBeNull()

        shouldThrow<SignatureVerificationException> {
            Algorithm.HMAC256("a-different-test-signing-key-with-more-than-32-bytes")
                .verify(JWT.decode(response.accessToken))
        }
    }

    test("UTF-8 credentials authenticate without normalizing distinct strings") {
        val username = "administratör"
        val password = "réservé-password-123"
        val auth = AuthService(ApiSettings(username, testPasswordHash(password), AUTH_SECRET))

        auth.authenticates(username, password) shouldBe true
        auth.authenticates("administrator", password) shouldBe false
        auth.authenticates(username, "re\u0301serve\u0301-password-123") shouldBe false
        JWT.decode(auth.login(username, password).shouldNotBeNull().accessToken).subject shouldBe username
    }

    test("long passwords cannot authenticate by matching a truncated bcrypt prefix") {
        val password = "é".repeat(36)
        val auth = AuthService(ApiSettings(AUTH_USERNAME, testPasswordHash(password), AUTH_SECRET))

        auth.authenticates(AUTH_USERNAME, password) shouldBe true
        auth.authenticates(AUTH_USERNAME, password + "a") shouldBe false
        auth.login(AUTH_USERNAME, password + "a").shouldBeNull()
    }

    test("a plaintext password cannot be configured as a stored password hash") {
        shouldThrow<IllegalArgumentException> { ApiSettings(AUTH_USERNAME, AUTH_PASSWORD, AUTH_SECRET) }
    }
})

private data class CredentialCase(val username: String, val password: String, val valid: Boolean)

private fun authSettings(lifetime: Long = 300) = ApiSettings(
    adminUsername = AUTH_USERNAME,
    adminPasswordHash = testPasswordHash(AUTH_PASSWORD),
    jwtSecret = AUTH_SECRET,
    jwtIssuer = "unit-test-issuer",
    jwtAudience = "unit-test-admin-audience",
    tokenTtlSeconds = lifetime,
    refreshTokenTtlSeconds = maxOf(600, lifetime),
)

private const val AUTH_USERNAME = "test-admin"
private const val AUTH_PASSWORD = "test-admin-password-123"
private const val AUTH_SECRET = "test-signing-key-with-at-least-thirty-two-bytes"
