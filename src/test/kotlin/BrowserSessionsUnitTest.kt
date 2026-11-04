package info.remzi.currencyquiz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant

class BrowserSessionsUnitTest : FunSpec({
    test("new sessions use separate opaque credentials and expose only metadata") {
        val fixture = SessionFixture()
        val issued = fixture.sessions.create()

        issued.cookieValue shouldMatch OPAQUE_CREDENTIAL
        issued.refreshValue shouldMatch OPAQUE_CREDENTIAL
        issued.session.csrfToken shouldMatch OPAQUE_CREDENTIAL
        setOf(issued.cookieValue, issued.refreshValue, issued.session.csrfToken).size shouldBe 3
        fixture.sessions.find(issued.cookieValue) shouldBe issued.session
        fixture.sessions.findRefresh(issued.refreshValue) shouldBe issued.session
        fixture.sessions.find(issued.refreshValue).shouldBeNull()
        fixture.sessions.findRefresh(issued.cookieValue).shouldBeNull()

        val response = fixture.sessions.response(issued.session)
        response.username shouldBe SESSION_USERNAME
        response.expiresIn shouldBe 900L
        response.accessExpiresIn shouldBe 300L
        response.absoluteExpiresIn shouldBe 1800L
        response.csrfToken shouldBe issued.session.csrfToken
        issued.session.toString().shouldNotContain(issued.session.csrfToken)
        issued.toString().shouldNotContain(issued.cookieValue)
        issued.toString().shouldNotContain(issued.refreshValue)
        BrowserSessionPrincipal(issued.session).toString().shouldNotContain(issued.session.csrfToken)
    }

    context("invalid credentials never authenticate or revoke an active session") {
        withTests(
            mapOf(
                "absent" to null,
                "empty" to "",
                "whitespace" to " ".repeat(43),
                "too short" to "a".repeat(42),
                "too long" to "a".repeat(44),
                "invalid alphabet" to "+".repeat(43),
                "padded base64" to ("a".repeat(42) + "="),
                "unknown well-formed value" to "a".repeat(43),
            ),
        ) { candidate ->
            val fixture = SessionFixture()
            val issued = fixture.sessions.create()

            fixture.sessions.find(candidate).shouldBeNull()
            fixture.sessions.findRefresh(candidate).shouldBeNull()
            fixture.sessions.revoke(candidate)
            fixture.sessions.find(issued.cookieValue) shouldBe issued.session
            fixture.sessions.findRefresh(issued.refreshValue) shouldBe issued.session
        }
    }

    test("access expires at its exact deadline while refresh remains usable") {
        val fixture = SessionFixture()
        val issued = fixture.sessions.create()

        fixture.advanceMillis(299_999)
        fixture.sessions.find(issued.cookieValue) shouldBe issued.session
        fixture.sessions.response(issued.session).accessExpiresIn shouldBe 1L
        fixture.advanceMillis(1)
        fixture.sessions.find(issued.cookieValue).shouldBeNull()
        fixture.sessions.response(issued.session).accessExpiresIn shouldBe 0L
        fixture.sessions.findRefresh(issued.refreshValue) shouldBe issued.session
        val rotated = fixture.sessions.rotate(issued.refreshValue).shouldNotBeNull()
        fixture.sessions.find(rotated.cookieValue) shouldBe issued.session
        fixture.sessions.response(issued.session).expiresIn shouldBe 600L
    }

    test("refresh replaces both credentials and retains CSRF and the original deadlines") {
        val fixture = SessionFixture()
        val issued = fixture.sessions.create()
        val deadline = issued.session.absoluteExpiresAt
        val lastActivity = issued.session.lastActivityAt
        fixture.advanceMillis(200_000)

        val rotated = fixture.sessions.rotate(issued.refreshValue).shouldNotBeNull()

        rotated.cookieValue shouldNotBe issued.cookieValue
        rotated.refreshValue shouldNotBe issued.refreshValue
        rotated.session shouldBe issued.session
        rotated.session.absoluteExpiresAt shouldBe deadline
        rotated.session.lastActivityAt shouldBe lastActivity
        fixture.sessions.validCsrf(rotated.session, issued.session.csrfToken) shouldBe true
        fixture.sessions.find(issued.cookieValue).shouldBeNull()
        fixture.sessions.findRefresh(issued.refreshValue).shouldBeNull()
        fixture.sessions.findRefresh(issued.refreshValue, includeUsed = true) shouldBe issued.session
        fixture.sessions.findRefresh(rotated.refreshValue) shouldBe issued.session
        fixture.sessions.response(rotated.session).expiresIn shouldBe 700L
    }

    test("replaying any used refresh credential revokes every token in its family") {
        val fixture = SessionFixture()
        val original = fixture.sessions.create()
        val second = fixture.sessions.rotate(original.refreshValue).shouldNotBeNull()
        val third = fixture.sessions.rotate(second.refreshValue).shouldNotBeNull()
        val unrelated = fixture.sessions.create()

        fixture.sessions.rotate(original.refreshValue).shouldBeNull()

        for (issued in listOf(original, second, third)) {
            fixture.sessions.find(issued.cookieValue).shouldBeNull()
            fixture.sessions.findRefresh(issued.refreshValue, includeUsed = true).shouldBeNull()
        }
        fixture.sessions.recordActivity(third.session) shouldBe false
        fixture.sessions.find(unrelated.cookieValue) shouldBe unrelated.session
    }

    test("the refresh rotation limit revokes the family instead of growing its history indefinitely") {
        val fixture = SessionFixture()
        val first = fixture.sessions.create()
        var current = first
        repeat(128) { current = fixture.sessions.rotate(current.refreshValue).shouldNotBeNull() }

        fixture.sessions.rotate(current.refreshValue).shouldBeNull()

        fixture.sessions.find(current.cookieValue).shouldBeNull()
        fixture.sessions.findRefresh(first.refreshValue, includeUsed = true).shouldBeNull()
        fixture.sessions.findRefresh(current.refreshValue, includeUsed = true).shouldBeNull()
    }

    test("idle expiry invalidates refresh at the exact boundary even after repeated reads and rotation") {
        val fixture = SessionFixture()
        var issued = fixture.sessions.create()
        repeat(2) {
            fixture.advanceMillis(299_000)
            fixture.sessions.findRefresh(issued.refreshValue).shouldNotBeNull()
            issued = fixture.sessions.rotate(issued.refreshValue).shouldNotBeNull()
        }
        fixture.advanceMillis(301_999)
        fixture.sessions.findRefresh(issued.refreshValue).shouldNotBeNull()
        fixture.sessions.response(issued.session).expiresIn shouldBe 1L
        fixture.advanceMillis(1)

        fixture.sessions.findRefresh(issued.refreshValue).shouldBeNull()
        fixture.sessions.rotate(issued.refreshValue).shouldBeNull()
        fixture.sessions.recordActivity(issued.session) shouldBe false
    }

    test("activity extends idle lifetime without extending access or the absolute deadline") {
        val fixture = SessionFixture()
        var issued = fixture.sessions.create()
        val originalAccessDeadline = issued.session.accessExpiresAt
        val absoluteDeadline = issued.session.absoluteExpiresAt
        fixture.advanceMillis(200_000)

        fixture.sessions.recordActivity(issued.session) shouldBe true
        issued.session.accessExpiresAt shouldBe originalAccessDeadline
        fixture.sessions.response(issued.session).expiresIn shouldBe 900L
        fixture.sessions.response(issued.session).accessExpiresIn shouldBe 100L
        repeat(5) {
            fixture.advanceMillis(299_000)
            issued = fixture.sessions.rotate(issued.refreshValue).shouldNotBeNull()
            fixture.sessions.recordActivity(issued.session) shouldBe true
        }
        issued.session.absoluteExpiresAt shouldBe absoluteDeadline
        fixture.sessions.response(issued.session).expiresIn shouldBe 105L
        fixture.sessions.response(issued.session).accessExpiresIn shouldBe 105L
        fixture.advanceMillis(105_000)

        fixture.sessions.find(issued.cookieValue).shouldBeNull()
        fixture.sessions.findRefresh(issued.refreshValue).shouldBeNull()
        fixture.sessions.recordActivity(issued.session) shouldBe false
    }

    context("either credential can revoke the complete session family") {
        withTests(mapOf("access credential" to false, "refresh credential" to true)) { refresh ->
            val fixture = SessionFixture()
            val original = fixture.sessions.create()
            val current = fixture.sessions.rotate(original.refreshValue).shouldNotBeNull()

            fixture.sessions.revoke(if (refresh) current.refreshValue else current.cookieValue)
            fixture.sessions.revoke(if (refresh) current.refreshValue else current.cookieValue)

            fixture.sessions.find(current.cookieValue).shouldBeNull()
            fixture.sessions.findRefresh(current.refreshValue).shouldBeNull()
            fixture.sessions.findRefresh(original.refreshValue, includeUsed = true).shouldBeNull()
            fixture.sessions.recordActivity(current.session) shouldBe false
        }
    }

    context("logging in again replaces a session using either existing credential") {
        withTests(mapOf("access credential" to false, "refresh credential" to true)) { refresh ->
            val fixture = SessionFixture()
            val original = fixture.sessions.create()
            val replacement = if (refresh) fixture.sessions.create(previousRefresh = original.refreshValue)
                else fixture.sessions.create(previousCookie = original.cookieValue)

            fixture.sessions.find(original.cookieValue).shouldBeNull()
            fixture.sessions.findRefresh(original.refreshValue).shouldBeNull()
            replacement.session.csrfToken shouldNotBe original.session.csrfToken
            fixture.sessions.find(replacement.cookieValue) shouldBe replacement.session
            fixture.sessions.findRefresh(replacement.refreshValue) shouldBe replacement.session
        }
    }

    test("eviction removes access, active refresh, and used refresh credentials of the oldest session") {
        val fixture = SessionFixture(maximumSessions = 2)
        val first = fixture.sessions.create()
        val rotated = fixture.sessions.rotate(first.refreshValue).shouldNotBeNull()
        val second = fixture.sessions.create()
        fixture.sessions.find(rotated.cookieValue).shouldNotBeNull()
        val third = fixture.sessions.create()

        fixture.sessions.find(rotated.cookieValue).shouldBeNull()
        fixture.sessions.findRefresh(rotated.refreshValue).shouldBeNull()
        fixture.sessions.findRefresh(first.refreshValue, includeUsed = true).shouldBeNull()
        fixture.sessions.find(second.cookieValue) shouldBe second.session
        fixture.sessions.find(third.cookieValue) shouldBe third.session
    }

    context("invalid session-store capacities fail immediately") {
        withTests(0, -1) { maximumSessions ->
            shouldThrow<IllegalArgumentException> { SessionFixture(maximumSessions = maximumSessions) }
        }
    }

    context("CSRF tokens must be well formed and bound to this session") {
        withTests(
            mapOf(
                "absent" to null,
                "empty" to "",
                "too short" to "a".repeat(42),
                "too long" to "a".repeat(44),
                "invalid alphabet" to "+".repeat(43),
                "incorrect token" to "a".repeat(43),
            ),
        ) { supplied ->
            val fixture = SessionFixture()
            val first = fixture.sessions.create()
            val second = fixture.sessions.create()

            fixture.sessions.validCsrf(first.session, supplied) shouldBe false
            fixture.sessions.validCsrf(first.session, second.session.csrfToken) shouldBe false
            fixture.sessions.validCsrf(first.session, first.session.csrfToken) shouldBe true
        }
    }

    context("only an exact configured admin origin is trusted") {
        withTests(
            mapOf(
                "absent" to null,
                "null origin" to "null",
                "public wildcard" to "*",
                "public consumer" to "https://public-consumer.example",
                "untrusted origin" to "https://untrusted.example",
                "different port" to "https://admin.example:8443",
                "trailing slash" to "$ADMIN_ORIGIN/",
                "hostname suffix" to "$ADMIN_ORIGIN.attacker.example",
            ),
        ) { origin ->
            val fixture = SessionFixture()

            fixture.sessions.trustedOrigin(origin) shouldBe false
            fixture.sessions.trustedOrigin(ADMIN_ORIGIN) shouldBe true
        }
    }

    context("cookie policy is applied to access and refresh credentials") {
        withTests(
            mapOf(
                "secure Strict" to (true to "Strict"),
                "secure Lax" to (true to "Lax"),
                "secure None" to (true to "None"),
                "local HTTP Strict" to (false to "Strict"),
            ),
        ) { (secure, sameSite) ->
            val fixture = SessionFixture(secure = secure, sameSite = sameSite)
            val issued = fixture.sessions.create()
            fixture.advanceMillis(250)

            for (refresh in listOf(false, true)) {
                val value = if (refresh) issued.refreshValue else issued.cookieValue
                val cookie = fixture.sessions.cookie(value, refresh = refresh, session = issued.session)
                cookie.name shouldBe if (refresh) ADMIN_REFRESH_COOKIE_NAME else ADMIN_COOKIE_NAME
                cookie.path shouldBe if (refresh) ADMIN_REFRESH_COOKIE_PATH else ADMIN_COOKIE_PATH
                cookie.value shouldBe value
                cookie.maxAge shouldBe if (refresh) 1800 else 300
                cookie.secure shouldBe secure
                cookie.httpOnly shouldBe true
                cookie.extensions["SameSite"] shouldBe sameSite
                cookie.domain.shouldBeNull()
                fixture.sessions.cookie("", clearing = true, refresh = refresh).maxAge shouldBe 0
            }
        }
    }

    test("cookie lifetimes round up remaining fractions and reach zero at the deadline") {
        val fixture = SessionFixture()
        val issued = fixture.sessions.create()
        fixture.advanceMillis(299_999)

        fixture.sessions.cookie(issued.cookieValue, session = issued.session).maxAge shouldBe 1
        fixture.advanceMillis(1)
        fixture.sessions.cookie(issued.cookieValue, session = issued.session).maxAge shouldBe 0
        fixture.advanceMillis(1_500_000)
        fixture.sessions.cookie(issued.refreshValue, refresh = true, session = issued.session).maxAge shouldBe 0
    }

    test("non-clearing cookies require session metadata for their expiration") {
        val fixture = SessionFixture()

        shouldThrow<IllegalArgumentException> { fixture.sessions.cookie("opaque-access") }
        shouldThrow<IllegalArgumentException> { fixture.sessions.cookie("opaque-refresh", refresh = true) }
    }
})

/** Each test owns its strict clock mock; advancing time never sleeps or changes global state. */
private class SessionFixture(
    maximumSessions: Int = 1_000,
    secure: Boolean = true,
    sameSite: String = "Strict",
) {
    private var now = Instant.parse("2026-11-04T12:00:00Z")
    private val clock = mockk<Clock> {
        every { instant() } answers { now }
        every { millis() } answers { now.toEpochMilli() }
    }
    val sessions = BrowserSessions(
        ApiSettings(
            adminUsername = SESSION_USERNAME,
            adminPassword = "test-admin-password-123",
            jwtSecret = "test-signing-key-with-at-least-thirty-two-bytes",
            allowedOrigins = listOf("*"),
            adminAllowedOrigins = listOf(ADMIN_ORIGIN),
            authCookieSecure = secure,
            authCookieSameSite = sameSite,
        ),
        clock,
        maximumSessions,
    )

    fun advanceMillis(milliseconds: Long) { now = now.plusMillis(milliseconds) }
}

private const val SESSION_USERNAME = "session-test-admin"
private const val ADMIN_ORIGIN = "https://admin.example"
private val OPAQUE_CREDENTIAL = Regex("^[A-Za-z0-9_-]{43}$")
