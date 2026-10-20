package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

class AuthService(private val settings: ApiSettings) {
    private val usernameHash = hash(settings.adminUsername)
    private val passwordHash = hash(settings.adminPassword)
    private val algorithm = Algorithm.HMAC256(settings.jwtSecret)

    fun login(username: String, password: String): TokenResponse? {
        // Compare both fixed-size hashes even when the username is unknown.
        val usernameMatches = MessageDigest.isEqual(usernameHash, hash(username))
        val passwordMatches = MessageDigest.isEqual(passwordHash, hash(password))
        if (!usernameMatches || !passwordMatches) return null

        val now = Instant.now()
        val token = JWT.create()
            .withIssuer(settings.jwtIssuer)
            .withAudience(settings.jwtAudience)
            .withSubject(settings.adminUsername)
            .withClaim("role", "admin")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(settings.tokenTtlSeconds)))
            .sign(algorithm)
        return TokenResponse(accessToken = token, expiresIn = settings.tokenTtlSeconds)
    }

    private fun hash(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}

fun Application.configureSecurity(settings: ApiSettings) {
    authentication {
        jwt("admin-jwt") {
            realm = "currencyquiz-admin"
            verifier(
                JWT.require(Algorithm.HMAC256(settings.jwtSecret))
                    .withAudience(settings.jwtAudience)
                    .withIssuer(settings.jwtIssuer)
                    .build()
            )
            validate { credential ->
                val payload = credential.payload
                if (payload.subject == settings.adminUsername &&
                    payload.getClaim("role").asString() == "admin" &&
                    payload.expiresAt?.after(Date()) == true
                ) {
                    JWTPrincipal(payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"currencyquiz-admin\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(code = "unauthorized", message = "A valid admin access token is required"),
                )
            }
        }
    }
}
