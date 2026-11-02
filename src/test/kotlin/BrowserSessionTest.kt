package info.remzi.currencyquiz

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class BrowserSessionTest {
    @Test
    fun `browser session cookie supports restore CRUD and revoked logout without exposing credentials`() = withApi {
        val login = client.browserLogin()
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        assertEquals("no-store", login.headers[HttpHeaders.CacheControl])
        val body = login.json().jsonObject
        assertEquals(setOf("username", "expiresIn", "accessExpiresIn", "absoluteExpiresIn", "csrfToken"), body.keys)
        assertEquals(USERNAME, body.getValue("username").jsonPrimitive.content)
        assertEquals(900L, body.getValue("expiresIn").jsonPrimitive.long)
        assertEquals(300L, body.getValue("accessExpiresIn").jsonPrimitive.long)
        assertEquals(1800L, body.getValue("absoluteExpiresIn").jsonPrimitive.long)
        val csrf = body.getValue("csrfToken").jsonPrimitive.content
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(csrf))
        val cookie = login.cookie()
        assertTrue(Regex("^currency_quiz_admin=[A-Za-z0-9_-]{43}$").matches(cookie))
        assertCookieFlags(login, secure = true)
        assertFalse(login.bodyAsText().contains(cookie.substringAfter('=')))
        assertFalse(login.bodyAsText().contains(PASSWORD))

        // Same-origin fetch commonly omits Origin on GET; only the HttpOnly cookie restores the session.
        val restored = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals("no-store", restored.headers[HttpHeaders.CacheControl])
        assertEquals(csrf, restored.json().jsonObject.getValue("csrfToken").jsonPrimitive.content)
        assertEquals(body.getValue("username"), restored.json().jsonObject.getValue("username"))
        assertNull(restored.headers[HttpHeaders.SetCookie], "A read must not extend the session lifetime")

        val created = client.post("/api/v1/questions") {
            browserCredentials(cookie, csrf)
            jsonBody(QUESTION)
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.json().jsonObject.getValue("id").jsonPrimitive.content
        val publicView = client.get("/api/v1/questions/$id").json().jsonObject
        assertFalse(publicView.containsKey("correctAnswer"))
        val updated = client.patch("/api/v1/questions/$id") {
            browserCredentials(cookie, csrf)
            jsonBody("""{"text":"Updated question?"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("Updated question?", updated.json().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/questions/$id") {
            browserCredentials(cookie, csrf)
        }.status)

        val logout = client.delete(SESSION_PATH) { browserCredentials(cookie, csrf) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals("", logout.bodyAsText())
        assertCookieFlags(logout, secure = true, clearing = true)
        assertEquals("no-store", logout.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Unauthorized, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/questions") {
            browserCredentials(cookie, csrf)
            jsonBody(QUESTION)
        }.status, "An old cookie must stay revoked even if manually replayed")
    }

    @Test
    fun `cookie mutations require trusted Origin and matching session-bound CSRF before changing content`() = withApi {
        val first = client.browserLogin()
        val cookie = first.cookie()
        val csrf = first.csrf()
        val otherCsrf = client.browserLogin().csrf()
        val created = client.post("/api/v1/questions") { browserCredentials(cookie, csrf); jsonBody(QUESTION) }
        val id = created.json().jsonObject.getValue("id").jsonPrimitive.content
        val invalidHeaders = listOf(
            null to csrf,
            "null" to csrf,
            "https://untrusted.example" to csrf,
            ORIGIN to null,
            ORIGIN to "invalid-csrf",
            ORIGIN to otherCsrf,
        )
        for ((origin, token) in invalidHeaders) {
            val rejected = listOf(
                client.post("/api/v1/questions") {
                    browserCredentials(cookie, token, origin); jsonBody(QUESTION)
                },
                client.patch("/api/v1/questions/$id") {
                    browserCredentials(cookie, token, origin); jsonBody("""{"text":"Must not change"}""")
                },
                client.delete("/api/v1/questions/$id") { browserCredentials(cookie, token, origin) },
                client.delete(SESSION_PATH) { browserCredentials(cookie, token, origin) },
            )
            for (response in rejected) assertEquals(HttpStatusCode.Forbidden, response.status)
            val listing = client.get("/api/v1/questions").json().jsonArray
            assertEquals(1, listing.size)
            assertEquals("What is the currency of Japan?", listing.single().jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status)
        }
        val duplicate = client.delete(SESSION_PATH) {
            browserCredentials(cookie, csrf)
            header(CSRF_HEADER, csrf)
        }
        assertEquals(HttpStatusCode.Forbidden, duplicate.status)
    }

    @Test
    fun `browser login requires explicit origin and JSON and rejects invalid credentials`() = withApi {
        for (origin in listOf(null, "null", "https://untrusted.example", "$ORIGIN.attacker.example")) {
            val response = client.browserLogin(origin = origin)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.SetCookie])
        }
        val nonJson = client.post(SESSION_PATH) {
            header(HttpHeaders.Origin, ORIGIN)
            contentType(ContentType.Text.Plain)
            setBody("username=$USERNAME&password=$PASSWORD")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, nonJson.status)
        val invalid = client.browserLogin(password = "incorrect-password")
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals("no-store", invalid.headers[HttpHeaders.CacheControl])
        assertNull(invalid.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun `missing forged expired or restarted sessions cannot authenticate`() {
        val clock = MutableClock()
        val settings = settings(ttl = 2)
        val sessions = BrowserSessions(settings, clock)
        withApi(settings, sessions) {
            val login = client.browserLogin()
            val cookie = login.cookie()
            val csrf = login.csrf()
            for (candidate in listOf(null, "currency_quiz_admin=not-valid", "currency_quiz_admin=${"a".repeat(43)}")) {
                val read = client.get(SESSION_PATH) { candidate?.let { header(HttpHeaders.Cookie, it) } }
                assertEquals(HttpStatusCode.Unauthorized, read.status)
                val write = client.post("/api/v1/questions") {
                    browserCredentials(candidate, csrf); jsonBody(QUESTION)
                }
                assertEquals(HttpStatusCode.Unauthorized, write.status)
            }
            clock.advance(Duration.ofSeconds(2))
            assertEquals(HttpStatusCode.Unauthorized, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/questions") {
                browserCredentials(cookie, csrf); jsonBody(QUESTION)
            }.status)
            val expiredLogout = client.delete(SESSION_PATH) { browserCredentials(cookie, csrf) }
            assertEquals(HttpStatusCode.Unauthorized, expiredLogout.status)
            assertCookieFlags(expiredLogout, secure = true, clearing = true)
            assertNull(BrowserSessions(settings, clock).find(cookie.substringAfter('=')), "A new process/store cannot restore old sessions")
        }
    }

    @Test
    fun `explicit invalid bearer credential never falls back to an otherwise valid cookie`() = withApi {
        val login = client.browserLogin()
        val cookie = login.cookie()
        val csrf = login.csrf()
        val response = client.post("/api/v1/questions") {
            browserCredentials(cookie, csrf)
            bearerAuth("invalid-bearer-token")
            jsonBody(QUESTION)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val bearerLogin = client.post("/api/v1/auth/login") { jsonBody(credentials()) }
        assertNull(bearerLogin.headers[HttpHeaders.SetCookie])
        val bearer = bearerLogin.json().jsonObject.getValue("accessToken").jsonPrimitive.content
        val bearerWrite = client.post("/api/v1/questions") { bearerAuth(bearer); jsonBody(QUESTION) }
        assertEquals(HttpStatusCode.Created, bearerWrite.status, "Bearer clients do not need browser CSRF headers")
    }

    @Test
    fun `relogin rotates session and CSRF and revokes replaced cookie`() = withApi {
        val first = client.browserLogin()
        val second = client.post(SESSION_PATH) {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Cookie, first.cookie())
            jsonBody(credentials())
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertNotEquals(first.cookie(), second.cookie())
        assertNotEquals(first.csrf(), second.csrf())
        assertEquals(HttpStatusCode.Unauthorized, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, first.cookie()) }.status)
        assertEquals(HttpStatusCode.OK, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, second.cookie()) }.status)
    }

    @Test
    fun `bounded session store evicts oldest sessions and cleans expired entries`() {
        val clock = MutableClock()
        val settings = settings(ttl = 5)
        val sessions = BrowserSessions(settings, clock, maximumSessions = 2)
        val first = sessions.create()
        val second = sessions.create()
        assertNotNull(sessions.find(first.cookieValue))
        val third = sessions.create()
        assertNull(sessions.find(first.cookieValue))
        assertNotNull(sessions.find(second.cookieValue))
        assertNotNull(sessions.find(third.cookieValue))
        clock.advance(Duration.ofSeconds(5))
        assertNull(sessions.find(second.cookieValue))
        assertNull(sessions.find(third.cookieValue))
        assertNotNull(sessions.find(sessions.create().cookieValue))
    }

    @Test
    fun `only explicit admin origins receive credentialed CORS while public wildcard remains public`() = withApi {
        val public = client.get("/api/v1/questions") { header(HttpHeaders.Origin, "https://public-consumer.example") }
        assertEquals(HttpStatusCode.OK, public.status)
        assertEquals("*", public.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(public.headers[HttpHeaders.AccessControlAllowCredentials])
        assertContains(public.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString(), "Origin")

        val login = client.browserLogin()
        assertEquals(ORIGIN, login.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", login.headers[HttpHeaders.AccessControlAllowCredentials])
        val restore = client.get(SESSION_PATH) { browserCredentials(login.cookie(), null) }
        assertEquals(HttpStatusCode.OK, restore.status)
        assertEquals(ORIGIN, restore.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", restore.headers[HttpHeaders.AccessControlAllowCredentials])
        val rejected = client.get(SESSION_PATH) {
            browserCredentials(login.cookie(), null, "https://public-consumer.example")
        }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertNull(rejected.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(rejected.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun `admin CORS preflight permits CSRF header only for exact trusted origins`() = withApi {
        val valid = client.options(SESSION_PATH) {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "DELETE")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type,x-csrf-token")
        }
        assertEquals(HttpStatusCode.OK, valid.status)
        assertEquals(ORIGIN, valid.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", valid.headers[HttpHeaders.AccessControlAllowCredentials])
        assertContains(valid.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty(), "x-csrf-token")
        for ((origin, path, method, headers) in listOf(
            listOf("https://public-consumer.example", SESSION_PATH, "POST", "content-type"),
            listOf("https://public-consumer.example", "/api/v1/questions", "POST", "x-csrf-token"),
            listOf(ORIGIN, SESSION_PATH, "PUT", "content-type"),
            listOf(ORIGIN, SESSION_PATH, "POST", "x-unexpected-header"),
        )) {
            val response = client.options(path) {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.AccessControlRequestMethod, method)
                header(HttpHeaders.AccessControlRequestHeaders, headers)
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `bearer and browser logins share the login rate limit`() = withApi {
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, client.browserLogin(password = "incorrect-password").status) }
        repeat(2) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/login") {
                jsonBody(credentials("incorrect-password"))
            }.status)
        }
        val throttled = client.browserLogin()
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", throttled.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/questions").status)
    }

    @Test
    fun `local HTTP and secure cross-site cookies use explicitly configured attributes`() {
        withApi(settings(secure = false)) { assertCookieFlags(client.browserLogin(), secure = false) }
        withApi(settings(sameSite = "None")) { assertCookieFlags(client.browserLogin(), secure = true, sameSite = "None") }
        assertFailsWith<IllegalArgumentException> { settings(secure = false, sameSite = "None") }
    }

    @Test
    fun `refresh rotates both credentials and replay revokes the family`() {
        val clock = MutableClock()
        val configuration = settings()
        withApi(configuration, BrowserSessions(configuration, clock)) {
            val login = client.browserLogin()
            val access = login.cookie()
            val refresh = login.refreshCookie()
            val csrf = login.csrf()
            val refreshAttributes = login.headers.getAll(HttpHeaders.SetCookie).orEmpty().last().lowercase()
            assertContains(refreshAttributes, "httponly")
            assertContains(refreshAttributes, "secure")
            assertContains(refreshAttributes, "path=/api/v1/auth/session")
            assertContains(refreshAttributes, "samesite=strict")
            clock.advance(Duration.ofSeconds(300))
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/questions") {
                browserCredentials(access, csrf); jsonBody(QUESTION)
            }.status)
            val read = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, refresh) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(0L, read.json().jsonObject.getValue("accessExpiresIn").jsonPrimitive.long)
            val denied = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, "b".repeat(43)) }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            val rotated = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
            assertEquals(HttpStatusCode.OK, rotated.status)
            assertEquals("no-store", rotated.headers[HttpHeaders.CacheControl])
            assertNotEquals(access, rotated.cookie())
            assertNotEquals(refresh, rotated.refreshCookie())
            assertEquals(600L, rotated.json().jsonObject.getValue("expiresIn").jsonPrimitive.long, "Refresh must not reset idle expiry")
            assertEquals(HttpStatusCode.OK, client.post("$SESSION_PATH/activity") { browserCredentials(rotated.cookie(), csrf) }.status)
            val replay = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(SESSION_PATH) { header(HttpHeaders.Cookie, rotated.refreshCookie()) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/questions") {
                browserCredentials(rotated.cookie(), csrf); jsonBody(QUESTION)
            }.status)
        }
    }

    @Test
    fun `refresh and reads cannot extend fifteen minute idle deadline`() {
        val clock = MutableClock()
        val sessions = BrowserSessions(settings(), clock)
        var issued = sessions.create()
        repeat(3) {
            clock.advance(Duration.ofSeconds(299))
            assertNotNull(sessions.findRefresh(issued.refreshValue))
            issued = assertNotNull(sessions.rotate(issued.refreshValue))
        }
        assertEquals(3L, sessions.response(issued.session).expiresIn)
        clock.advance(Duration.ofSeconds(3))
        assertNull(sessions.find(issued.cookieValue))
        assertNull(sessions.findRefresh(issued.refreshValue))
        assertNull(sessions.rotate(issued.refreshValue))
    }

    @Test
    fun `activity resets idle expiry but never the thirty minute absolute deadline`() {
        val clock = MutableClock()
        val sessions = BrowserSessions(settings(), clock)
        var issued = sessions.create()
        repeat(7) {
            clock.advance(Duration.ofSeconds(240))
            issued = assertNotNull(sessions.rotate(issued.refreshValue))
            assertTrue(sessions.recordActivity(issued.session))
        }
        assertEquals(120L, sessions.response(issued.session).expiresIn)
        assertEquals(120L, sessions.response(issued.session).absoluteExpiresIn)
        clock.advance(Duration.ofSeconds(120))
        assertNull(sessions.find(issued.cookieValue))
        assertNull(sessions.findRefresh(issued.refreshValue))
        assertFalse(sessions.recordActivity(issued.session))
    }

    @Test
    fun `refresh alone never grants question access and logout revokes both tokens`() = withApi {
        val login = client.browserLogin()
        val refresh = login.refreshCookie()
        val csrf = login.csrf()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/questions") {
            browserCredentials(refresh, csrf); jsonBody(QUESTION)
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$SESSION_PATH/activity") { browserCredentials(refresh, csrf) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf, null) }.status)
        val logout = client.delete(SESSION_PATH) { browserCredentials(refresh, csrf) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(2, logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().size)
        logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { assertContains(it.lowercase(), "max-age=0") }
        assertEquals(HttpStatusCode.Unauthorized, client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }.status)
    }

    private fun assertCookieFlags(response: HttpResponse, secure: Boolean, clearing: Boolean = false, sameSite: String = "Strict") {
        val raw = assertNotNull(response.headers[HttpHeaders.SetCookie])
        val attributes = raw.lowercase().split(';').map(String::trim)
        assertContains(attributes, "path=/api/v1")
        assertContains(attributes, "httponly")
        assertEquals(secure, "secure" in attributes)
        assertContains(attributes, "samesite=${sameSite.lowercase()}")
        assertContains(attributes, "max-age=${if (clearing) 0 else 300}")
        assertFalse(attributes.any { it.startsWith("domain=") })
    }

    private fun withApi(
        settings: ApiSettings = settings(),
        sessions: BrowserSessions = BrowserSessions(settings),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val directory = Files.createTempDirectory("currencyquiz-browser-session-test-")
        try {
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, "[]")
            testApplication {
                environment { config = MapApplicationConfig() }
                application { quizModule(settings, FileQuestionRepository(storage), sessions) }
                block()
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun settings(ttl: Long = 300, secure: Boolean = true, sameSite: String = "Strict") = ApiSettings(
        adminUsername = USERNAME,
        adminPassword = PASSWORD,
        jwtSecret = "test-signing-key-with-at-least-thirty-two-bytes",
        tokenTtlSeconds = ttl,
        allowedOrigins = listOf("*"),
        adminAllowedOrigins = listOf(ORIGIN),
        authCookieSecure = secure,
        authCookieSameSite = sameSite,
    )

    private suspend fun HttpClient.browserLogin(origin: String? = ORIGIN, password: String = PASSWORD) = post(SESSION_PATH) {
        origin?.let { header(HttpHeaders.Origin, it) }
        jsonBody(credentials(password))
    }

    private fun HttpRequestBuilder.browserCredentials(cookie: String?, csrf: String?, origin: String? = ORIGIN) {
        cookie?.let { header(HttpHeaders.Cookie, it) }
        origin?.let { header(HttpHeaders.Origin, it) }
        csrf?.let { header(CSRF_HEADER, it) }
    }

    private fun credentials(password: String = PASSWORD) = """{"username":"$USERNAME","password":"$password"}"""
    private fun HttpRequestBuilder.jsonBody(body: String) { contentType(ContentType.Application.Json); setBody(body) }
    private fun HttpResponse.cookie() = assertNotNull(headers[HttpHeaders.SetCookie]).substringBefore(';')
    private fun HttpResponse.refreshCookie() = headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .single { it.startsWith("$ADMIN_REFRESH_COOKIE_NAME=") }.substringBefore(';')
    private suspend fun HttpResponse.csrf() = json().jsonObject.getValue("csrfToken").jsonPrimitive.content
    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private class MutableClock : Clock() {
        private var current = Instant.parse("2026-11-02T12:00:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    companion object {
        private const val USERNAME = "test-admin"
        private const val PASSWORD = "test-admin-password-123"
        private const val ORIGIN = "http://localhost:5173"
        private const val SESSION_PATH = "/api/v1/auth/session"
        private const val QUESTION = """{"text":"What is the currency of Japan?","options":["JPY","EUR"],"correctAnswer":"JPY"}"""
    }
}
