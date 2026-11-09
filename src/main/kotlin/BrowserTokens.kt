package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import info.remzi.currencyquiz.contract.BrowserSessionResponse
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.security.MessageDigest

internal const val ADMIN_COOKIE_NAME = "currency_quiz_admin"
internal const val ADMIN_COOKIE_PATH = "/api/v1"
internal const val ADMIN_REFRESH_COOKIE_NAME = "currency_quiz_admin_refresh"
internal const val ADMIN_REFRESH_COOKIE_PATH = "/api/v1/auth/session"
internal const val CSRF_HEADER = "X-CSRF-Token"

/** Cookie transport and CSRF checks are independent of JWT authentication. */
class BrowserTokens(private val settings: ApiSettings, private val tokens: JwtTokens) {
    fun metadata(access: DecodedJWT?, refresh: DecodedJWT?): BrowserSessionResponse? {
        val credential = refresh ?: access ?: return null
        val remaining = tokens.secondsRemaining(credential)
        if (remaining == 0L) return null
        val matchingAccess = access?.takeIf { it.getClaim("csrf").asString() == credential.getClaim("csrf").asString() }
        return BrowserSessionResponse(
            username = credential.subject,
            expiresIn = remaining,
            accessExpiresIn = matchingAccess?.let(tokens::secondsRemaining) ?: 0,
            csrfToken = credential.getClaim("csrf").asString(),
        )
    }

    /** Outbound only: the value is issued by this server; incoming cookies are always verified separately. */
    internal fun cookie(value: String, refresh: Boolean = false, clearing: Boolean = false) = Cookie(
        name = if (refresh) ADMIN_REFRESH_COOKIE_NAME else ADMIN_COOKIE_NAME,
        value = value,
        path = if (refresh) ADMIN_REFRESH_COOKIE_PATH else ADMIN_COOKIE_PATH,
        maxAge = if (clearing) 0 else tokens.secondsRemaining(JWT.decode(value)).toInt(),
        secure = settings.authCookieSecure,
        httpOnly = true,
        extensions = mapOf("SameSite" to settings.authCookieSameSite),
    )

    fun trustedOrigin(origin: String?): Boolean = origin != null && origin in settings.adminAllowedOrigins

    fun validCsrf(token: Payload, supplied: String?): Boolean = supplied != null && CSRF_PATTERN.matches(supplied) &&
        MessageDigest.isEqual(token.getClaim("csrf").asString().toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))
}

internal suspend fun ApplicationCall.requireBrowserOrigin(browser: BrowserTokens, allowAbsent: Boolean = false): Boolean {
    val origins = request.headers.getAll(HttpHeaders.Origin)
    if ((origins == null && allowAbsent) || (origins?.size == 1 && browser.trustedOrigin(origins.single()))) return true
    respond(HttpStatusCode.Forbidden, apiError("forbidden", "The browser origin is not allowed"))
    return false
}

internal suspend fun ApplicationCall.requireBrowserCsrf(browser: BrowserTokens, token: Payload): Boolean {
    if (!requireBrowserOrigin(browser)) return false
    val supplied = request.headers.getAll(CSRF_HEADER)
    if (supplied?.size == 1 && browser.validCsrf(token, supplied.single())) return true
    respond(HttpStatusCode.Forbidden, apiError("forbidden", "A valid CSRF token is required"))
    return false
}
