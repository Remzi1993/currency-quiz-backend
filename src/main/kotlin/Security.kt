package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.TokenResponse
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest

class AuthService(private val settings: ApiSettings, private val tokens: JwtTokens = JwtTokens(settings)) {
    private val usernameHash = hash(settings.adminUsername)

    fun authenticates(username: String, password: String): Boolean {
        // Perform the expensive password check even for an unknown username.
        val usernameMatches = MessageDigest.isEqual(usernameHash, hash(username))
        val passwordMatches = PasswordHash.verifies(password, settings.adminPasswordHash)
        return usernameMatches && passwordMatches
    }

    fun login(username: String, password: String): TokenResponse? =
        if (authenticates(username, password)) TokenResponse(accessToken = tokens.issueAccess(), expiresIn = settings.tokenTtlSeconds)
        else null

    private fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}

fun Application.configureSecurity(tokens: JwtTokens) {
    authentication {
        jwt("admin-jwt") {
            realm = "currency-quiz-admin"
            authHeader { call ->
                // An explicit Authorization header never falls back to the browser cookie.
                val headers = call.request.headers.getAll(HttpHeaders.Authorization)
                if (headers != null) {
                    headers.singleOrNull()?.let { Regex("(?i)^Bearer ([^\\s]+)$").matchEntire(it)?.groupValues?.get(1) }
                        ?.let { HttpAuthHeader.Single("Bearer", it) }
                } else call.request.cookies[ADMIN_COOKIE_NAME]?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            verifier(object : JWTVerifier {
                override fun verify(token: String): DecodedJWT =
                    tokens.verifyAccess(token) ?: throw JWTVerificationException("Invalid access token")
                override fun verify(jwt: DecodedJWT): DecodedJWT = verify(jwt.token)
            })
            validate { credential -> JWTPrincipal(credential.payload) }
            challenge { _, _ ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"currency-quiz-admin\"")
                call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "A valid admin access token is required"))
            }
        }
    }
}
