package info.remzi.currencyquiz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.*

class BrowserSessionTest : FunSpec() {
    init {
        test("browser session cookie supports restore CRUD and revoked logout without exposing credentials") {
            withApi {
                val login = client.browserLogin()
                withClue(login.bodyAsText()) { login.status shouldBe HttpStatusCode.OK }
                login.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = login.json().jsonObject
                body.keys shouldBe setOf("username", "expiresIn", "accessExpiresIn", "absoluteExpiresIn", "csrfToken")
                body.getValue("username").jsonPrimitive.content shouldBe USERNAME
                body.getValue("expiresIn").jsonPrimitive.long shouldBe 900L
                body.getValue("accessExpiresIn").jsonPrimitive.long shouldBe 300L
                body.getValue("absoluteExpiresIn").jsonPrimitive.long shouldBe 1800L
                val csrf = body.getValue("csrfToken").jsonPrimitive.content
                Regex("^[A-Za-z0-9_-]{43}$").matches(csrf) shouldBe true
                val cookie = login.cookie()
                Regex("^currency_quiz_admin=[A-Za-z0-9_-]{43}$").matches(cookie) shouldBe true
                assertCookieFlags(login, secure = true)
                login.bodyAsText().contains(cookie.substringAfter('=')) shouldBe false
                login.bodyAsText().contains(PASSWORD) shouldBe false

                // Same-origin fetch commonly omits Origin on GET; only the HttpOnly cookie restores the session.
                val restored = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }
                restored.status shouldBe HttpStatusCode.OK
                restored.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                restored.json().jsonObject.getValue("csrfToken").jsonPrimitive.content shouldBe csrf
                restored.json().jsonObject.getValue("username") shouldBe body.getValue("username")
                withClue("A read must not extend the session lifetime") { restored.headers[HttpHeaders.SetCookie].shouldBeNull() }

                val created = client.post("/api/v1/questions") {
                    browserCredentials(cookie, csrf)
                    jsonBody(QUESTION)
                }
                withClue(created.bodyAsText()) { created.status shouldBe HttpStatusCode.Created }
                val id = created.json().jsonObject.getValue("id").jsonPrimitive.content
                val publicView = client.get("/api/v1/questions/$id").json().jsonObject
                publicView.containsKey("correctAnswer") shouldBe false
                val updated = client.patch("/api/v1/questions/$id") {
                    browserCredentials(cookie, csrf)
                    jsonBody("""{"text":"Updated question?"}""")
                }
                updated.status shouldBe HttpStatusCode.OK
                updated.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Updated question?"
                client.delete("/api/v1/questions/$id") {
                    browserCredentials(cookie, csrf)
                }.status shouldBe HttpStatusCode.NoContent

                val logout = client.delete(SESSION_PATH) { browserCredentials(cookie, csrf) }
                logout.status shouldBe HttpStatusCode.NoContent
                logout.bodyAsText() shouldBe ""
                assertCookieFlags(logout, secure = true, clearing = true)
                logout.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.Unauthorized
                withClue("An old cookie must stay revoked even if manually replayed") { client.post("/api/v1/questions") {
                    browserCredentials(cookie, csrf)
                    jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized }
            }
        }

        test("cookie mutations require trusted Origin and matching session-bound CSRF before changing content") {
            withApi {
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
                    for (response in rejected) response.status shouldBe HttpStatusCode.Forbidden
                    val listing = client.get("/api/v1/questions").json().jsonArray
                    listing.size shouldBe 1
                    listing.single().jsonObject.getValue("text").jsonPrimitive.content shouldBe "What is the currency of Japan?"
                    client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.OK
                }
                val duplicate = client.delete(SESSION_PATH) {
                    browserCredentials(cookie, csrf)
                    header(CSRF_HEADER, csrf)
                }
                duplicate.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("browser login requires explicit origin and JSON and rejects invalid credentials") {
            withApi {
                for (origin in listOf(null, "null", "https://untrusted.example", "$ORIGIN.attacker.example")) {
                    val response = client.browserLogin(origin = origin)
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.headers[HttpHeaders.SetCookie].shouldBeNull()
                }
                val nonJson = client.post(SESSION_PATH) {
                    header(HttpHeaders.Origin, ORIGIN)
                    contentType(ContentType.Text.Plain)
                    setBody("username=$USERNAME&password=$PASSWORD")
                }
                nonJson.status shouldBe HttpStatusCode.UnsupportedMediaType
                val invalid = client.browserLogin(password = "incorrect-password")
                invalid.status shouldBe HttpStatusCode.Unauthorized
                invalid.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                invalid.headers[HttpHeaders.SetCookie].shouldBeNull()
            }
        }

        test("missing forged expired or restarted sessions cannot authenticate") {
            val clock = MutableClock()
            val settings = settings(ttl = 2)
            val sessions = BrowserSessions(settings, clock)
            withApi(settings, sessions) {
                val login = client.browserLogin()
                val cookie = login.cookie()
                val csrf = login.csrf()
                for (candidate in listOf(null, "currency_quiz_admin=not-valid", "currency_quiz_admin=${"a".repeat(43)}")) {
                    val read = client.get(SESSION_PATH) { candidate?.let { header(HttpHeaders.Cookie, it) } }
                    read.status shouldBe HttpStatusCode.Unauthorized
                    val write = client.post("/api/v1/questions") {
                        browserCredentials(candidate, csrf); jsonBody(QUESTION)
                    }
                    write.status shouldBe HttpStatusCode.Unauthorized
                }
                clock.advance(Duration.ofSeconds(2))
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.Unauthorized
                client.post("/api/v1/questions") {
                    browserCredentials(cookie, csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
                val expiredLogout = client.delete(SESSION_PATH) { browserCredentials(cookie, csrf) }
                expiredLogout.status shouldBe HttpStatusCode.Unauthorized
                assertCookieFlags(expiredLogout, secure = true, clearing = true)
                withClue("A new process/store cannot restore old sessions") { BrowserSessions(settings, clock).find(cookie.substringAfter('=')).shouldBeNull() }
            }
        }

        test("explicit invalid bearer credential never falls back to an otherwise valid cookie") {
            withApi {
                val login = client.browserLogin()
                val cookie = login.cookie()
                val csrf = login.csrf()
                val response = client.post("/api/v1/questions") {
                    browserCredentials(cookie, csrf)
                    bearerAuth("invalid-bearer-token")
                    jsonBody(QUESTION)
                }
                response.status shouldBe HttpStatusCode.Unauthorized
                val bearerLogin = client.post("/api/v1/auth/login") { jsonBody(credentials()) }
                bearerLogin.headers[HttpHeaders.SetCookie].shouldBeNull()
                val bearer = bearerLogin.json().jsonObject.getValue("accessToken").jsonPrimitive.content
                val bearerWrite = client.post("/api/v1/questions") { bearerAuth(bearer); jsonBody(QUESTION) }
                withClue("Bearer clients do not need browser CSRF headers") { bearerWrite.status shouldBe HttpStatusCode.Created }
            }
        }

        test("relogin rotates session and CSRF and revokes replaced cookie") {
            withApi {
                val first = client.browserLogin()
                val second = client.post(SESSION_PATH) {
                    header(HttpHeaders.Origin, ORIGIN)
                    header(HttpHeaders.Cookie, first.cookie())
                    jsonBody(credentials())
                }
                second.status shouldBe HttpStatusCode.OK
                second.cookie() shouldNotBe first.cookie()
                second.csrf() shouldNotBe first.csrf()
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, first.cookie()) }.status shouldBe HttpStatusCode.Unauthorized
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, second.cookie()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("bounded session store evicts oldest sessions and cleans expired entries") {
            val clock = MutableClock()
            val settings = settings(ttl = 5)
            val sessions = BrowserSessions(settings, clock, maximumSessions = 2)
            val first = sessions.create()
            val second = sessions.create()
            sessions.find(first.cookieValue).shouldNotBeNull()
            val third = sessions.create()
            sessions.find(first.cookieValue).shouldBeNull()
            sessions.find(second.cookieValue).shouldNotBeNull()
            sessions.find(third.cookieValue).shouldNotBeNull()
            clock.advance(Duration.ofSeconds(5))
            sessions.find(second.cookieValue).shouldBeNull()
            sessions.find(third.cookieValue).shouldBeNull()
            sessions.find(sessions.create().cookieValue).shouldNotBeNull()
        }

        test("only explicit admin origins receive credentialed CORS while public wildcard remains public") {
            withApi {
                val public = client.get("/api/v1/questions") { header(HttpHeaders.Origin, "https://public-consumer.example") }
                public.status shouldBe HttpStatusCode.OK
                public.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "*"
                public.headers[HttpHeaders.AccessControlAllowCredentials].shouldBeNull()
                public.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString().shouldContain("Origin")

                val login = client.browserLogin()
                login.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
                login.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe "true"
                val restore = client.get(SESSION_PATH) { browserCredentials(login.cookie(), null) }
                restore.status shouldBe HttpStatusCode.OK
                restore.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
                restore.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe "true"
                val rejected = client.get(SESSION_PATH) {
                    browserCredentials(login.cookie(), null, "https://public-consumer.example")
                }
                rejected.status shouldBe HttpStatusCode.Forbidden
                rejected.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
                rejected.headers[HttpHeaders.AccessControlAllowCredentials].shouldBeNull()
            }
        }

        test("admin CORS preflight permits CSRF header only for exact trusted origins") {
            withApi {
                val valid = client.options(SESSION_PATH) {
                    header(HttpHeaders.Origin, ORIGIN)
                    header(HttpHeaders.AccessControlRequestMethod, "DELETE")
                    header(HttpHeaders.AccessControlRequestHeaders, "content-type,x-csrf-token")
                }
                valid.status shouldBe HttpStatusCode.OK
                valid.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe ORIGIN
                valid.headers[HttpHeaders.AccessControlAllowCredentials] shouldBe "true"
                valid.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().shouldContain("x-csrf-token")
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
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("bearer and browser logins share the login rate limit") {
            withApi {
                repeat(3) { client.browserLogin(password = "incorrect-password").status shouldBe HttpStatusCode.Unauthorized }
                repeat(2) {
                    client.post("/api/v1/auth/login") {
                        jsonBody(credentials("incorrect-password"))
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                val throttled = client.browserLogin()
                throttled.status shouldBe HttpStatusCode.TooManyRequests
                throttled.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                throttled.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                client.get("/api/v1/questions").status shouldBe HttpStatusCode.OK
            }
        }

        test("local HTTP and secure cross-site cookies use explicitly configured attributes") {
            withApi(settings(secure = false)) { assertCookieFlags(client.browserLogin(), secure = false) }
            withApi(settings(sameSite = "None")) { assertCookieFlags(client.browserLogin(), secure = true, sameSite = "None") }
            shouldThrow<IllegalArgumentException> { settings(secure = false, sameSite = "None") }
        }

        test("refresh rotates both credentials and replay revokes the family") {
            val clock = MutableClock()
            val configuration = settings()
            withApi(configuration, BrowserSessions(configuration, clock)) {
                val login = client.browserLogin()
                val access = login.cookie()
                val refresh = login.refreshCookie()
                val csrf = login.csrf()
                val refreshAttributes = login.headers.getAll(HttpHeaders.SetCookie).orEmpty().last().lowercase()
                refreshAttributes.shouldContain("httponly")
                refreshAttributes.shouldContain("secure")
                refreshAttributes.shouldContain("path=/api/v1/auth/session")
                refreshAttributes.shouldContain("samesite=strict")
                clock.advance(Duration.ofSeconds(300))
                client.post("/api/v1/questions") {
                    browserCredentials(access, csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
                val read = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, refresh) }
                read.status shouldBe HttpStatusCode.OK
                read.json().jsonObject.getValue("accessExpiresIn").jsonPrimitive.long shouldBe 0L
                val denied = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, "b".repeat(43)) }
                denied.status shouldBe HttpStatusCode.Forbidden
                val rotated = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
                rotated.status shouldBe HttpStatusCode.OK
                rotated.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                rotated.cookie() shouldNotBe access
                rotated.refreshCookie() shouldNotBe refresh
                withClue("Refresh must not reset idle expiry") { rotated.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe 600L }
                client.post("$SESSION_PATH/activity") { browserCredentials(rotated.cookie(), csrf) }.status shouldBe HttpStatusCode.OK
                val replay = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
                replay.status shouldBe HttpStatusCode.Unauthorized
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, rotated.refreshCookie()) }.status shouldBe HttpStatusCode.Unauthorized
                client.post("/api/v1/questions") {
                    browserCredentials(rotated.cookie(), csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("refresh and reads cannot extend fifteen minute idle deadline") {
            val clock = MutableClock()
            val sessions = BrowserSessions(settings(), clock)
            var issued = sessions.create()
            repeat(3) {
                clock.advance(Duration.ofSeconds(299))
                sessions.findRefresh(issued.refreshValue).shouldNotBeNull()
                issued = sessions.rotate(issued.refreshValue).shouldNotBeNull()
            }
            sessions.response(issued.session).expiresIn shouldBe 3L
            clock.advance(Duration.ofSeconds(3))
            sessions.find(issued.cookieValue).shouldBeNull()
            sessions.findRefresh(issued.refreshValue).shouldBeNull()
            sessions.rotate(issued.refreshValue).shouldBeNull()
        }

        test("activity resets idle expiry but never the thirty minute absolute deadline") {
            val clock = MutableClock()
            val sessions = BrowserSessions(settings(), clock)
            var issued = sessions.create()
            repeat(7) {
                clock.advance(Duration.ofSeconds(240))
                issued = sessions.rotate(issued.refreshValue).shouldNotBeNull()
                sessions.recordActivity(issued.session) shouldBe true
            }
            sessions.response(issued.session).expiresIn shouldBe 120L
            sessions.response(issued.session).absoluteExpiresIn shouldBe 120L
            clock.advance(Duration.ofSeconds(120))
            sessions.find(issued.cookieValue).shouldBeNull()
            sessions.findRefresh(issued.refreshValue).shouldBeNull()
            sessions.recordActivity(issued.session) shouldBe false
        }

        test("refresh alone never grants question access and logout revokes both tokens") {
            withApi {
                val login = client.browserLogin()
                val refresh = login.refreshCookie()
                val csrf = login.csrf()
                client.post("/api/v1/questions") {
                    browserCredentials(refresh, csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
                client.post("$SESSION_PATH/activity") { browserCredentials(refresh, csrf) }.status shouldBe HttpStatusCode.Unauthorized
                client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf, null) }.status shouldBe HttpStatusCode.Forbidden
                val logout = client.delete(SESSION_PATH) { browserCredentials(refresh, csrf) }
                logout.status shouldBe HttpStatusCode.NoContent
                logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { it.lowercase().shouldContain("max-age=0") }
                client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    }

    private fun assertCookieFlags(response: HttpResponse, secure: Boolean, clearing: Boolean = false, sameSite: String = "Strict") {
        val raw = response.headers[HttpHeaders.SetCookie].shouldNotBeNull()
        val attributes = raw.lowercase().split(';').map(String::trim)
        attributes.shouldContain("path=/api/v1")
        attributes.shouldContain("httponly")
        ("secure" in attributes) shouldBe secure
        attributes.shouldContain("samesite=${sameSite.lowercase()}")
        attributes.shouldContain("max-age=${if (clearing) 0 else 300}")
        attributes.any { it.startsWith("domain=") } shouldBe false
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
    private fun HttpResponse.cookie() = headers[HttpHeaders.SetCookie].shouldNotBeNull().substringBefore(';')
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
