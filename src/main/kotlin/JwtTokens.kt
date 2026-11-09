package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Verifies self-contained credentials; no issued tokens or client activity are retained. */
class JwtTokens(private val settings: ApiSettings, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(settings.jwtSecret)
    private val random = SecureRandom()
    private val accessVerifier = verifier("access")
    private val refreshVerifier = verifier("refresh")

    fun newCsrfToken(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun issueAccess(csrfToken: String = newCsrfToken(), deadline: Instant? = null): String {
        val now = clock.instant()
        return issue("access", csrfToken, now, minOf(now.plusSeconds(settings.tokenTtlSeconds), deadline ?: Instant.MAX))
    }

    fun issueRefresh(csrfToken: String): String {
        val now = clock.instant()
        return issue("refresh", csrfToken, now, now.plusSeconds(settings.refreshTokenTtlSeconds))
    }

    fun verifyAccess(token: String?): DecodedJWT? = verify(token, accessVerifier, "access", settings.tokenTtlSeconds)
    fun verifyRefresh(token: String?): DecodedJWT? = verify(token, refreshVerifier, "refresh", settings.refreshTokenTtlSeconds)

    fun secondsRemaining(token: DecodedJWT): Long =
        (token.expiresAtAsInstant.epochSecond - clock.instant().epochSecond).coerceAtLeast(0)

    private fun issue(purpose: String, csrf: String, issuedAt: Instant, expiresAt: Instant): String = JWT.create()
        .withHeader(mapOf("typ" to tokenType(purpose)))
        .withIssuer(settings.jwtIssuer)
        .withAudience(settings.jwtAudience)
        .withSubject(settings.adminUsername)
        .withClaim("role", "admin")
        .withClaim("token_use", purpose)
        .withClaim("csrf", csrf)
        .withJWTId(UUID.randomUUID().toString())
        .withIssuedAt(issuedAt)
        .withExpiresAt(expiresAt)
        .sign(algorithm)

    private fun verifier(purpose: String): JWTVerifier {
        val verification = JWT.require(algorithm)
            .withIssuer(settings.jwtIssuer)
            .withAudience(settings.jwtAudience)
            .withSubject(settings.adminUsername)
            .withClaim("role", "admin")
            .withClaim("token_use", purpose)
            .withClaimPresence("iat")
            .withClaimPresence("exp")
        return (verification as JWTVerifier.BaseVerification).build(clock)
    }

    private fun verify(value: String?, verifier: JWTVerifier, purpose: String, lifetime: Long): DecodedJWT? {
        if (value == null || value.length > 4096) return null
        return try {
            val token = verifier.verify(value)
            val issued = token.issuedAtAsInstant ?: return null
            val expires = token.expiresAtAsInstant ?: return null
            token.takeIf {
                token.type == tokenType(purpose) &&
                    expires.epochSecond - issued.epochSecond in 1..lifetime &&
                    CSRF_PATTERN.matches(token.getClaim("csrf").asString().orEmpty())
            }
        } catch (_: JWTVerificationException) {
            null
        }
    }

    private fun tokenType(purpose: String) = if (purpose == "access") "at+jwt" else "refresh+jwt"
}

internal val CSRF_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
