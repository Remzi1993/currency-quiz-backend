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
        test("browser JWT cookies support restore CRUD and logout without exposing credentials") {
            withApi {
                val login = client.browserLogin()
                withClue(login.bodyAsText()) { login.status shouldBe HttpStatusCode.OK }
                login.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = login.json().jsonObject
                body.keys shouldBe setOf("username", "expiresIn", "accessExpiresIn", "csrfToken")
                body.getValue("username").jsonPrimitive.content shouldBe USERNAME
                body.getValue("expiresIn").jsonPrimitive.long shouldBe 600L
                body.getValue("accessExpiresIn").jsonPrimitive.long shouldBe 300L
                val csrf = body.getValue("csrfToken").jsonPrimitive.content
                Regex("^[A-Za-z0-9_-]{43}$").matches(csrf) shouldBe true
                val cookie = login.cookie()
                cookie.substringAfter('=').split('.').size shouldBe 3
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

        test("missing forged or expired access JWTs cannot authenticate") {
            val clock = MutableClock()
            val settings = settings(ttl = 2)
            val tokens = JwtTokens(settings, clock)
            withApi(settings, tokens) {
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

        test("relogin issues independent credentials and CSRF without retaining old credentials") {
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
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, first.cookie()) }.status shouldBe HttpStatusCode.OK
                client.get(SESSION_PATH) { header(HttpHeaders.Cookie, second.cookie()) }.status shouldBe HttpStatusCode.OK
            }
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

        test("refresh renews both credentials after access expiry and retains CSRF protection") {
            val clock = MutableClock()
            val configuration = settings()
            withApi(configuration, JwtTokens(configuration, clock)) {
                val login = client.browserLogin()
                val access = login.cookie()
                val refresh = login.refreshCookie()
                val csrf = login.csrf()
                val refreshAttributes = login.headers.getAll(HttpHeaders.SetCookie).orEmpty().last().lowercase()
                refreshAttributes.shouldContain("httponly")
                refreshAttributes.shouldContain("secure")
                refreshAttributes.shouldContain("path=/api/v1/auth/session")
                refreshAttributes.shouldContain("samesite=strict")
                refreshAttributes.shouldContain("max-age=600")
                clock.advance(Duration.ofSeconds(300))
                client.post("/api/v1/questions") {
                    browserCredentials(access, csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
                val read = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, refresh) }
                read.status shouldBe HttpStatusCode.OK
                read.json().jsonObject.getValue("accessExpiresIn").jsonPrimitive.long shouldBe 0L
                read.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe 300L
                read.headers[HttpHeaders.SetCookie].shouldBeNull()
                val denied = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, "b".repeat(43)) }
                denied.status shouldBe HttpStatusCode.Forbidden
                val renewed = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
                renewed.status shouldBe HttpStatusCode.OK
                renewed.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                renewed.cookie() shouldNotBe access
                renewed.refreshCookie() shouldNotBe refresh
                renewed.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                renewed.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe 600L
                renewed.json().jsonObject.getValue("accessExpiresIn").jsonPrimitive.long shouldBe 300L
                renewed.csrf() shouldBe csrf
                client.post("/api/v1/questions") {
                    browserCredentials(renewed.cookie(), csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Created
                // There is no server registry to mark refresh JWTs as spent.
                client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("rolling refresh ends at the configured absolute login deadline") {
            val clock = MutableClock()
            val configuration = settings(absoluteSeconds = 1200)
            withApi(configuration, JwtTokens(configuration, clock)) {
                var latest = client.browserLogin()
                val csrf = latest.csrf()
                for ((elapsed, remaining) in listOf(299L to 600L, 300L to 600L, 300L to 301L, 300L to 1L)) {
                    val previousRefresh = latest.refreshCookie()
                    clock.advance(Duration.ofSeconds(elapsed))
                    latest = client.post("$SESSION_PATH/refresh") { browserCredentials(previousRefresh, csrf) }
                    latest.status shouldBe HttpStatusCode.OK
                    latest.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                    latest.refreshCookie() shouldNotBe previousRefresh
                    latest.csrf() shouldBe csrf
                    latest.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe remaining
                    latest.json().jsonObject.getValue("accessExpiresIn").jsonPrimitive.long shouldBe minOf(300L, remaining)
                }
                val access = latest.cookie()
                val refresh = latest.refreshCookie()
                val read = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, "$access; $refresh") }
                read.status shouldBe HttpStatusCode.OK
                read.headers[HttpHeaders.SetCookie].shouldBeNull()
                clock.advance(Duration.ofSeconds(1))
                client.get(SESSION_PATH) {
                    header(HttpHeaders.Cookie, "$access; $refresh")
                }.status shouldBe HttpStatusCode.Unauthorized
                client.post("/api/v1/questions") {
                    browserCredentials(access, csrf); jsonBody(QUESTION)
                }.status shouldBe HttpStatusCode.Unauthorized
                val expired = client.post("$SESSION_PATH/refresh") { browserCredentials(refresh, csrf) }
                expired.status shouldBe HttpStatusCode.Unauthorized
                expired.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                expired.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach {
                    it.lowercase().shouldContain("max-age=0")
                }
            }
        }

        test("reads cannot keep an unused refresh token alive beyond ten minutes") {
            val clock = MutableClock()
            val configuration = settings()
            withApi(configuration, JwtTokens(configuration, clock)) {
                val login = client.browserLogin()
                val refresh = login.refreshCookie()
                val csrf = login.csrf()
                clock.advance(Duration.ofSeconds(599))
                val read = client.get(SESSION_PATH) { header(HttpHeaders.Cookie, refresh) }
                read.status shouldBe HttpStatusCode.OK
                read.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe 1L
                read.headers[HttpHeaders.SetCookie].shouldBeNull()
                clock.advance(Duration.ofSeconds(1))
                client.post("$SESSION_PATH/refresh") {
                    browserCredentials(refresh, csrf)
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
        test("refresh requires its own JWT and trusted CSRF protected requests") {
            withApi {
                val login = client.browserLogin()
                val refresh = login.refreshCookie()
                val csrf = login.csrf()
                for (cookie in listOf(null, login.cookie(), "$ADMIN_REFRESH_COOKIE_NAME=invalid-jwt")) {
                    client.post("$SESSION_PATH/refresh") {
                        browserCredentials(cookie, csrf)
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                client.post("$SESSION_PATH/refresh") {
                    browserCredentials(refresh, csrf, null)
                }.status shouldBe HttpStatusCode.Forbidden
                client.post("$SESSION_PATH/refresh") {
                    browserCredentials(refresh, null)
                }.status shouldBe HttpStatusCode.Forbidden
                client.post("$SESSION_PATH/refresh") {
                    browserCredentials(refresh, csrf)
                    header(CSRF_HEADER, csrf)
                }.status shouldBe HttpStatusCode.Forbidden
                client.post("$SESSION_PATH/refresh") {
                    browserCredentials(refresh, csrf)
                    header(HttpHeaders.Origin, ORIGIN)
                }.status shouldBe HttpStatusCode.Forbidden
                val logout = client.delete(SESSION_PATH) { browserCredentials(refresh, csrf) }
                logout.status shouldBe HttpStatusCode.NoContent
                logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach {
                    it.lowercase().shouldContain("max-age=0")
                }
                client.post("$SESSION_PATH/activity") {
                    browserCredentials(login.cookie(), csrf)
                }.status shouldBe HttpStatusCode.NotFound
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
        tokens: JwtTokens = JwtTokens(settings),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val directory = Files.createTempDirectory("currencyquiz-browser-session-test-")
        try {
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, "[]")
            testApplication {
                environment { config = MapApplicationConfig() }
                application { quizModule(settings, FileQuestionRepository(storage), tokens) }
                block()
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun settings(ttl: Long = 300, secure: Boolean = true, sameSite: String = "Strict", absoluteSeconds: Long = 86_400) = ApiSettings(
        adminUsername = USERNAME,
        adminPasswordHash = testPasswordHash(PASSWORD),
        jwtSecret = "test-signing-key-with-at-least-thirty-two-bytes",
        tokenTtlSeconds = ttl,
        absoluteTokenTtlSeconds = absoluteSeconds,
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
