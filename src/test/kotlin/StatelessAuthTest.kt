package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.*

class StatelessAuthTest : FunSpec() {
    init {
        test("browser credentials are signed JWTs with five and ten minute lifetimes") {
            withApi {
                val response = client.loginBrowser()
                val access = response.cookie(ACCESS_COOKIE).substringAfter('=')
                val refresh = response.cookie(REFRESH_COOKIE).substringAfter('=')
                for ((value, purpose, lifetime) in listOf(
                    Triple(access, "access", 300L),
                    Triple(refresh, "refresh", 600L),
                )) {
                    withClue("$purpose credentials must be JWTs rather than opaque server-side keys") {
                        value.split('.').size shouldBe 3
                    }
                    val token = JWT.decode(value)
                    Algorithm.HMAC256(SECRET).verify(token)
                    token.getClaim("token_use").asString() shouldBe purpose
                    token.expiresAtAsInstant.epochSecond - token.issuedAtAsInstant.epochSecond shouldBe lifetime
                }
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { cookie ->
                    val lifetime = if (cookie.startsWith("$REFRESH_COOKIE=")) 600 else 300
                    cookie.lowercase().contains("max-age=$lifetime") shouldBe true
                    cookie.lowercase().contains("httponly") shouldBe true
                    cookie.lowercase().contains("secure") shouldBe true
                    response.bodyAsText().contains(cookie.substringBefore(';').substringAfter('=')) shouldBe false
                }
            }
        }

        test("a fresh application verifies cookies without a shared session registry") {
            var accessCookie = ""
            var refreshCookie = ""
            withApi {
                val login = client.loginBrowser()
                accessCookie = login.cookie(ACCESS_COOKIE)
                refreshCookie = login.cookie(REFRESH_COOKIE)
            }
            withApi {
                for (cookie in listOf(accessCookie, refreshCookie)) {
                    val response = client.get(AUTH_PATH) { header(HttpHeaders.Cookie, cookie) }
                    withClue("A signed credential must survive an application restart") {
                        response.status shouldBe HttpStatusCode.OK
                    }
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        test("a refresh JWT never grants question management access") {
            withApi {
                val login = client.loginBrowser()
                val refresh = login.cookie(REFRESH_COOKIE).substringAfter('=')
                val csrf = login.csrf()
                val bearer = client.post("/api/v1/questions") {
                    bearerAuth(refresh)
                    questionBody()
                }
                bearer.status shouldBe HttpStatusCode.Unauthorized
                val cookie = client.post("/api/v1/questions") {
                    header(HttpHeaders.Cookie, "$ACCESS_COOKIE=$refresh")
                    header(HttpHeaders.Origin, ORIGIN)
                    header("X-CSRF-Token", csrf)
                    questionBody()
                }
                cookie.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("stateless logout clears both cookies while copied JWTs expire naturally") {
            withApi {
                val login = client.loginBrowser()
                val access = login.cookie(ACCESS_COOKIE)
                val refresh = login.cookie(REFRESH_COOKIE)
                val csrf = login.csrf()
                val logout = client.delete(AUTH_PATH) {
                    header(HttpHeaders.Cookie, "$access; $refresh")
                    header(HttpHeaders.Origin, ORIGIN)
                    header("X-CSRF-Token", csrf)
                }
                logout.status shouldBe HttpStatusCode.NoContent
                val cleared = logout.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                cleared.size shouldBe 2
                cleared.forEach { it.lowercase().contains("max-age=0") shouldBe true }
                for (cookie in listOf(access, refresh)) {
                    withClue("Stateless logout cannot revoke an already copied signed credential") {
                        client.get(AUTH_PATH) { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    }

    private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-stateless-auth-test-")
        try {
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, "[]")
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    quizModule(
                        ApiSettings(USERNAME, testPasswordHash(PASSWORD), SECRET, adminAllowedOrigins = listOf(ORIGIN)),
                        FileQuestionRepository(storage),
                    )
                }
                block()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun HttpClient.loginBrowser(): HttpResponse = post(AUTH_PATH) {
        header(HttpHeaders.Origin, ORIGIN)
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$USERNAME","password":"$PASSWORD"}""")
    }.also { withClue(it.bodyAsText()) { it.status shouldBe HttpStatusCode.OK } }

    private fun HttpRequestBuilder.questionBody() {
        contentType(ContentType.Application.Json)
        setBody("""{"text":"Currency of Japan?","options":["JPY","EUR"],"correctAnswer":"JPY"}""")
    }

    private fun HttpResponse.cookie(name: String): String = headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .singleOrNull { it.startsWith("$name=") }.shouldNotBeNull().substringBefore(';')

    private suspend fun HttpResponse.csrf(): String = Json.parseToJsonElement(bodyAsText())
        .jsonObject.getValue("csrfToken").jsonPrimitive.content

    companion object {
        private const val USERNAME = "stateless-test-admin"
        private const val PASSWORD = "stateless-test-password-123"
        private const val SECRET = "stateless-test-signing-key-with-at-least-32-bytes"
        private const val ORIGIN = "http://localhost:5173"
        private const val AUTH_PATH = "/api/v1/auth/session"
        private const val ACCESS_COOKIE = "currency_quiz_admin"
        private const val REFRESH_COOKIE = "currency_quiz_admin_refresh"
    }
}
