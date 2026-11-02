package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.BrowserSessionResponse
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap

internal const val ADMIN_COOKIE_NAME = "currency_quiz_admin"
internal const val ADMIN_COOKIE_PATH = "/api/v1"
internal const val ADMIN_REFRESH_COOKIE_NAME = "currency_quiz_admin_refresh"
internal const val ADMIN_REFRESH_COOKIE_PATH = "/api/v1/auth/session"
internal const val CSRF_HEADER = "X-CSRF-Token"

/** Opaque credentials are deliberately not data classes, so logging a principal omits secrets. */
class BrowserSession internal constructor(
    val username: String, val csrfToken: String, val absoluteExpiresAt: Instant,
    internal var lastActivityAt: Instant, internal var accessExpiresAt: Instant,
    internal var accessHash: String, internal var refreshHash: String,
) {
    internal val usedRefreshHashes = mutableSetOf<String>()
}
class IssuedBrowserSession internal constructor(val cookieValue: String, val refreshValue: String, val session: BrowserSession)
class BrowserSessionPrincipal(val session: BrowserSession)

/** A single-process prototype session store. Restarting the service revokes all browser sessions. */
class BrowserSessions(
    private val settings: ApiSettings,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumSessions: Int = 1_000,
) {
    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, BrowserSession>()
    private val accessTokens = mutableMapOf<String, BrowserSession>()
    private val refreshTokens = mutableMapOf<String, BrowserSession>()
    private val credentialPattern = Regex("^[A-Za-z0-9_-]{43}$")

    init { require(maximumSessions > 0) }

    @Synchronized
    fun create(previousCookie: String? = null, previousRefresh: String? = null): IssuedBrowserSession {
        pruneExpired()
        revoke(previousCookie)
        revoke(previousRefresh)
        while (sessions.size >= maximumSessions) {
            revokeSession(sessions.values.first())
        }
        val cookieValue = randomValue()
        val refreshValue = randomValue()
        val now = clock.instant()
        val session = BrowserSession(settings.adminUsername, randomValue(), now.plusSeconds(settings.sessionAbsoluteSeconds),
            now, now.plusSeconds(settings.tokenTtlSeconds), hash(cookieValue), hash(refreshValue))
        sessions[session.csrfToken] = session
        accessTokens[session.accessHash] = session
        refreshTokens[session.refreshHash] = session
        return IssuedBrowserSession(cookieValue, refreshValue, session)
    }

    @Synchronized
    fun find(cookieValue: String?): BrowserSession? {
        pruneExpired()
        if (cookieValue == null || !credentialPattern.matches(cookieValue)) return null
        return accessTokens[hash(cookieValue)]?.takeIf { it.accessExpiresAt.isAfter(clock.instant()) }
    }

    @Synchronized
    fun revoke(cookieValue: String?) {
        if (cookieValue == null || !credentialPattern.matches(cookieValue)) return
        val digest = hash(cookieValue)
        (accessTokens[digest] ?: refreshTokens[digest])?.let(::revokeSession)
    }

    /** Used refresh credentials are retained only for reuse detection, never for authentication. */
    @Synchronized
    fun findRefresh(cookieValue: String?, includeUsed: Boolean = false): BrowserSession? {
        pruneExpired()
        if (cookieValue == null || !credentialPattern.matches(cookieValue)) return null
        val digest = hash(cookieValue)
        return refreshTokens[digest]?.takeIf { includeUsed || it.refreshHash == digest }
    }

    @Synchronized
    fun rotate(refreshValue: String): IssuedBrowserSession? {
        val session = findRefresh(refreshValue, includeUsed = true) ?: return null
        if (session.refreshHash != hash(refreshValue) || session.usedRefreshHashes.size >= 128) {
            revokeSession(session)
            return null
        }
        val access = randomValue()
        val refresh = randomValue()
        accessTokens.remove(session.accessHash)
        session.usedRefreshHashes.add(session.refreshHash)
        session.accessHash = hash(access)
        session.refreshHash = hash(refresh)
        session.accessExpiresAt = minOf(clock.instant().plusSeconds(settings.tokenTtlSeconds), expiresAt(session))
        accessTokens[session.accessHash] = session
        refreshTokens[session.refreshHash] = session
        // Refresh never updates lastActivityAt or the absolute deadline.
        return IssuedBrowserSession(access, refresh, session)
    }

    @Synchronized
    fun recordActivity(session: BrowserSession): Boolean {
        pruneExpired()
        if (sessions[session.csrfToken] !== session) return false
        session.lastActivityAt = clock.instant()
        return true
    }

    @Synchronized
    fun response(session: BrowserSession) = BrowserSessionResponse(
        username = session.username,
        expiresIn = secondsRemaining(expiresAt(session)).coerceAtLeast(1),
        accessExpiresIn = secondsRemaining(session.accessExpiresAt),
        absoluteExpiresIn = secondsRemaining(session.absoluteExpiresAt).coerceAtLeast(1),
        csrfToken = session.csrfToken,
    )

    fun cookie(value: String, clearing: Boolean = false, refresh: Boolean = false, session: BrowserSession? = null) = Cookie(
        name = if (refresh) ADMIN_REFRESH_COOKIE_NAME else ADMIN_COOKIE_NAME,
        value = value,
        path = if (refresh) ADMIN_REFRESH_COOKIE_PATH else ADMIN_COOKIE_PATH,
        maxAge = if (clearing) 0 else if (refresh) secondsRemaining(requireNotNull(session).absoluteExpiresAt).toInt()
            else secondsRemaining(requireNotNull(session).accessExpiresAt).toInt(),
        secure = settings.authCookieSecure,
        httpOnly = true,
        extensions = mapOf("SameSite" to settings.authCookieSameSite),
    )

    fun trustedOrigin(origin: String?): Boolean = origin != null && origin in settings.adminAllowedOrigins

    fun validCsrf(session: BrowserSession, supplied: String?): Boolean = supplied != null
        && credentialPattern.matches(supplied)
        && MessageDigest.isEqual(session.csrfToken.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))

    private fun pruneExpired() {
        val now = clock.instant()
        sessions.values.filter { !expiresAt(it).isAfter(now) }.forEach(::revokeSession)
    }

    private fun expiresAt(session: BrowserSession) = minOf(session.lastActivityAt.plusSeconds(settings.sessionIdleSeconds), session.absoluteExpiresAt)
    private fun secondsRemaining(deadline: Instant) = ((deadline.toEpochMilli() - clock.millis() + 999) / 1_000).coerceAtLeast(0)
    private fun revokeSession(session: BrowserSession) {
        sessions.remove(session.csrfToken)
        accessTokens.remove(session.accessHash)
        refreshTokens.remove(session.refreshHash)
        session.usedRefreshHashes.forEach(refreshTokens::remove)
    }

    private fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun hash(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)))
}

internal suspend fun ApplicationCall.requireBrowserOrigin(sessions: BrowserSessions, allowAbsent: Boolean = false): Boolean {
    val origins = request.headers.getAll(HttpHeaders.Origin)
    if ((origins == null && allowAbsent) || (origins?.size == 1 && sessions.trustedOrigin(origins.single()))) return true
    respond(HttpStatusCode.Forbidden, apiError("forbidden", "The browser origin is not allowed"))
    return false
}

internal suspend fun ApplicationCall.requireBrowserCsrf(sessions: BrowserSessions, session: BrowserSession): Boolean {
    if (!requireBrowserOrigin(sessions)) return false
    val tokens = request.headers.getAll(CSRF_HEADER)
    if (tokens?.size == 1 && sessions.validCsrf(session, tokens.single())) return true
    respond(HttpStatusCode.Forbidden, apiError("forbidden", "A valid CSRF token is required"))
    return false
}
