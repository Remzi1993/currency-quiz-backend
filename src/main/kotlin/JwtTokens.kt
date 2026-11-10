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

/** Credentials are not a data class, so their string representation does not reveal JWTs. */
class IssuedTokens internal constructor(val accessToken: String, val refreshToken: String)

/** Verifies self-contained credentials; no issued tokens or client activity are retained. */
class JwtTokens(private val settings: ApiSettings, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(settings.jwtSecret)
    private val random = SecureRandom()
    private val accessVerifier = verifier("access")
    private val refreshVerifier = verifier("refresh")

    fun newCsrfToken(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun issueAccess(): String {
        val now = clock.instant()
        return issue("access", newCsrfToken(), now, now.epochSecond, now.epochSecond + settings.absoluteTokenTtlSeconds)
    }

    fun startLogin(): IssuedTokens {
        val now = clock.instant()
        return issuePair(newCsrfToken(), now, now.epochSecond, now.epochSecond + settings.absoluteTokenTtlSeconds)
    }

    /** The caller verifies the refresh JWT and its CSRF header before requesting renewal. */
    fun renew(refresh: DecodedJWT): IssuedTokens? {
        val now = clock.instant()
        val authenticatedAt = integerClaim(refresh, "auth_time") ?: return null
        val absoluteExpiry = integerClaim(refresh, "absolute_exp") ?: return null
        if (refresh.getClaim("token_use").asString() != "refresh" ||
            !refresh.expiresAtAsInstant.isAfter(now) || absoluteExpiry <= now.epochSecond) return null
        return issuePair(refresh.getClaim("csrf").asString(), now, authenticatedAt, absoluteExpiry)
    }

    fun verifyAccess(token: String?): DecodedJWT? = verify(token, accessVerifier, "access", settings.tokenTtlSeconds)
    fun verifyRefresh(token: String?): DecodedJWT? = verify(token, refreshVerifier, "refresh", settings.refreshTokenTtlSeconds)

    fun secondsRemaining(token: DecodedJWT): Long =
        (token.expiresAtAsInstant.epochSecond - clock.instant().epochSecond).coerceAtLeast(0)

    private fun issuePair(csrf: String, now: Instant, authenticatedAt: Long, absoluteExpiry: Long) = IssuedTokens(
        issue("access", csrf, now, authenticatedAt, absoluteExpiry),
        issue("refresh", csrf, now, authenticatedAt, absoluteExpiry),
    )

    private fun issue(purpose: String, csrf: String, now: Instant, authenticatedAt: Long, absoluteExpiry: Long): String {
        val lifetime = if (purpose == "access") settings.tokenTtlSeconds else settings.refreshTokenTtlSeconds
        return JWT.create()
            .withHeader(mapOf("typ" to tokenType(purpose)))
            .withIssuer(settings.jwtIssuer)
            .withAudience(settings.jwtAudience)
            .withSubject(settings.adminUsername)
            .withClaim("role", "admin")
            .withClaim("token_use", purpose)
            .withClaim("csrf", csrf)
            .withClaim("auth_time", authenticatedAt)
            .withClaim("absolute_exp", absoluteExpiry)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(now)
            .withExpiresAt(Instant.ofEpochSecond(minOf(now.epochSecond + lifetime, absoluteExpiry)))
            .sign(algorithm)
    }

    private fun verifier(purpose: String): JWTVerifier {
        val verification = JWT.require(algorithm)
            .withIssuer(settings.jwtIssuer)
            .withAudience(settings.jwtAudience)
            .withSubject(settings.adminUsername)
            .withClaim("role", "admin")
            .withClaim("token_use", purpose)
            .withClaimPresence("iat")
            .withClaimPresence("exp")
            .withClaimPresence("auth_time")
            .withClaimPresence("absolute_exp")
        return (verification as JWTVerifier.BaseVerification).build(clock)
    }

    private fun verify(value: String?, verifier: JWTVerifier, purpose: String, lifetime: Long): DecodedJWT? {
        if (value == null || value.length > 4096) return null
        return try {
            val token = verifier.verify(value)
            val issued = token.issuedAtAsInstant?.epochSecond ?: return null
            val expires = token.expiresAtAsInstant?.epochSecond ?: return null
            val authenticatedAt = integerClaim(token, "auth_time") ?: return null
            val absoluteExpiry = integerClaim(token, "absolute_exp") ?: return null
            val now = clock.instant().epochSecond
            token.takeIf {
                token.type == tokenType(purpose) &&
                    authenticatedAt >= 0 && authenticatedAt <= issued &&
                    absoluteExpiry > issued && absoluteExpiry - authenticatedAt <= settings.absoluteTokenTtlSeconds &&
                    expires > issued && expires <= absoluteExpiry && expires - issued <= lifetime &&
                    expires > now && absoluteExpiry > now &&
                    CSRF_PATTERN.matches(token.getClaim("csrf").asString().orEmpty())
            }
        } catch (_: JWTVerificationException) {
            null
        }
    }

    // Object conversion preserves JSON types; asLong() would silently truncate decimal claims.
    private fun integerClaim(token: DecodedJWT, name: String): Long? =
        when (val value = token.getClaim(name).`as`(Any::class.java)) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

    private fun tokenType(purpose: String) = if (purpose == "access") "at+jwt" else "refresh+jwt"
}

internal val CSRF_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
