package info.remzi.currencyquiz

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
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
import java.util.UUID
import kotlinx.serialization.json.*

class ContractTest : FunSpec() {
    private val validator by lazy {
        val specification = javaClass.getResourceAsStream("/documentation.yaml").shouldNotBeNull()
            .bufferedReader().use { it.readText() }
        OpenApiInteractionValidator.createForInlineApiSpecification(specification).build()
    }

    init {
        test("browser JWT login refresh and logout responses conform to shared contract") {
            withApi {
                val sessionPath = "/api/v1/auth/session"
                suspend fun check(response: HttpResponse, path: String, method: Request.Method, status: HttpStatusCode) {
                    response.status shouldBe status
                    val actual = SimpleResponse.Builder.status(response.status.value)
                    response.headers.entries().forEach { (name, values) -> actual.withHeader(name, values) }
                    val body = response.bodyAsText()
                    if (body.isNotEmpty()) actual.withBody(body)
                    val report = validator.validateResponse(path, method, actual.build())
                    withClue(report.messages.joinToString { it.message }) { report.hasErrors() shouldBe false }
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
                val login = client.post(sessionPath) {
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""")
                }
                check(login, sessionPath, Request.Method.POST, HttpStatusCode.OK)
                val csrf = login.json().jsonObject.getValue("csrfToken").jsonPrimitive.content
                fun HttpResponse.cookies() = headers.getAll(HttpHeaders.SetCookie).orEmpty().joinToString("; ") { it.substringBefore(';') }
                val read = client.get(sessionPath) { header(HttpHeaders.Cookie, login.cookies()) }
                check(read, sessionPath, Request.Method.GET, HttpStatusCode.OK)
                val refreshed = client.post("$sessionPath/refresh") {
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header(HttpHeaders.Cookie, login.cookies())
                    header(CSRF_HEADER, csrf)
                }
                check(refreshed, "$sessionPath/refresh", Request.Method.POST, HttpStatusCode.OK)
                refreshed.headers.getAll(HttpHeaders.SetCookie).orEmpty().size shouldBe 2
                refreshed.json().jsonObject.getValue("expiresIn").jsonPrimitive.long shouldBe 600L
                val logout = client.delete(sessionPath) {
                    header(HttpHeaders.Origin, "http://localhost:5173")
                    header(HttpHeaders.Cookie, refreshed.cookies())
                    header(CSRF_HEADER, csrf)
                }
                check(logout, sessionPath, Request.Method.DELETE, HttpStatusCode.NoContent)
            }
        }

        test("real quiz interactions conform to the bundled shared contract") {
            withApi {
                client.checkContract(HttpMethod.Get, "/api/v1/questions", HttpStatusCode.OK)
                val token = client.login()
                val created = client.checkContract(
                    HttpMethod.Post, "/api/v1/questions", HttpStatusCode.Created,
                    body = QUESTION_BODY, token = token,
                )
                val id = created.json().jsonObject.getValue("id").jsonPrimitive.content
                created.headers[HttpHeaders.Location] shouldBe "/api/v1/questions/$id"

                val listing = client.checkContract(HttpMethod.Get, "/api/v1/questions", HttpStatusCode.OK)
                val publicQuestion = client.checkContract(HttpMethod.Get, "/api/v1/questions/$id", HttpStatusCode.OK)
                publicQuestion.json().jsonObject.keys shouldBe setOf("id", "text", "options")
                listing.json().jsonArray.single().jsonObject.containsKey("correctAnswer") shouldBe false

                val wrongAnswer = client.checkContract(
                    HttpMethod.Post, "/api/v1/questions/$id/answers", HttpStatusCode.OK,
                    body = """{"answer":"EUR"}""",
                )
                wrongAnswer.json() shouldBe buildJsonObject { put("correct", false) }
                client.checkContract(
                    HttpMethod.Patch, "/api/v1/questions/$id", HttpStatusCode.OK, token = token,
                    body = """{"text":"Updated contract question?","options":null,"correctAnswer":null}""",
                )
                val deleted = client.checkContract(
                    HttpMethod.Delete, "/api/v1/questions/$id", HttpStatusCode.NoContent, token = token,
                )
                deleted.bodyAsText() shouldBe ""
            }
        }

        test("real invalid requests return errors described by the shared contract") {
            withApi {
                client.checkContract(
                    HttpMethod.Post, "/api/v1/questions", HttpStatusCode.Unauthorized,
                    body = QUESTION_BODY, validateRequest = false,
                )
                client.checkContract(
                    HttpMethod.Post, "/api/v1/auth/login", HttpStatusCode.Unauthorized,
                    body = INVALID_LOGIN_BODY,
                )
                val token = client.login()
                val id = client.checkContract(
                    HttpMethod.Post, "/api/v1/questions", HttpStatusCode.Created,
                    body = QUESTION_BODY, token = token,
                ).json().jsonObject.getValue("id").jsonPrimitive.content

                client.checkContract(
                    HttpMethod.Post, "/api/v1/questions", HttpStatusCode.BadRequest,
                    body = "{", token = token, validateRequest = false,
                )
                client.checkContract(
                    HttpMethod.Patch, "/api/v1/questions/$id", HttpStatusCode.BadRequest,
                    body = """{"text":null,"options":null,"correctAnswer":null}""",
                    token = token, validateRequest = false,
                )
                client.checkContract(
                    HttpMethod.Get, "/api/v1/questions/not-a-uuid", HttpStatusCode.BadRequest,
                    validateRequest = false,
                )
                client.checkContract(
                    HttpMethod.Get, "/api/v1/questions/${UUID.randomUUID()}", HttpStatusCode.NotFound,
                )
                client.checkContract(
                    HttpMethod.Post, "/api/v1/questions/$id/answers", HttpStatusCode.UnsupportedMediaType,
                    body = "JPY", contentType = ContentType.Text.Plain, validateRequest = false,
                )

                // Two login requests above have consumed two of the five available attempts.
                repeat(3) {
                    client.checkContract(
                        HttpMethod.Post, "/api/v1/auth/login", HttpStatusCode.Unauthorized,
                        body = INVALID_LOGIN_BODY,
                    )
                }
                val throttled = client.checkContract(
                    HttpMethod.Post, "/api/v1/auth/login", HttpStatusCode.TooManyRequests,
                    body = INVALID_LOGIN_BODY,
                )
                throttled.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
            }
        }

        test("contract validation catches an answer accidentally added to a public response") {
            val id = UUID.randomUUID().toString()
            val publicQuestion = buildJsonObject {
                put("id", id)
                put("text", "Contract confidentiality check?")
                putJsonArray("options") { add("JPY"); add("EUR") }
            }
            fun response(body: JsonObject) = SimpleResponse.Builder.ok()
                .withContentType("application/json")
                .withBody(body.toString())
                .build()

            val valid = validator.validateResponse("/api/v1/questions/$id", Request.Method.GET, response(publicQuestion))
            withClue(valid.messages.joinToString { it.message }) { valid.hasErrors() shouldBe false }

            val leaked = JsonObject(publicQuestion + ("correctAnswer" to JsonPrimitive("JPY")))
            val rejected = validator.validateResponse("/api/v1/questions/$id", Request.Method.GET, response(leaked))
            withClue("The shared schema must reject correctAnswer in a public response") { rejected.hasErrors() shouldBe true }
            withClue(rejected.messages.joinToString { it.message }) { rejected.messages.any {
                it.key.contains("additionalProperties", ignoreCase = true) || it.message.contains("correctAnswer")
            } shouldBe true }
        }

        test("shared PATCH schema requires a non-null field and permits null for other fields") {
            val path = "/api/v1/questions/${UUID.randomUUID()}"
            fun validate(body: String) = validator.validateRequest(
                SimpleRequest.Builder.patch(path)
                    .withContentType("application/json")
                    .withAuthorization("Bearer contract-schema-test-token")
                    .withBody(body)
                    .build(),
            )

            val usablePatches = listOf(
                """{"text":"Updated question?","options":null,"correctAnswer":null}""",
                """{"text":null,"options":["JPY","EUR"],"correctAnswer":null}""",
                """{"text":null,"options":null,"correctAnswer":"JPY"}""",
            )
            for (body in usablePatches) {
                val report = validate(body)
                withClue("Usable PATCH rejected: $body; ${report.messages.joinToString { it.message }}") { report.hasErrors() shouldBe false }
            }
            for (body in listOf("{}", """{"text":null}""", """{"text":null,"options":null,"correctAnswer":null}""")) {
                val report = validate(body)
                withClue("The shared PATCH schema must reject a request without a non-null field: $body") { report.hasErrors() shouldBe true }
            }
        }
    }

    private suspend fun HttpClient.login(): String = checkContract(
        HttpMethod.Post, "/api/v1/auth/login", HttpStatusCode.OK,
        body = """{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""",
    ).json().jsonObject.getValue("accessToken").jsonPrimitive.content

    private suspend fun HttpClient.checkContract(
        method: HttpMethod,
        path: String,
        expectedStatus: HttpStatusCode,
        body: String? = null,
        token: String? = null,
        contentType: ContentType = ContentType.Application.Json,
        validateRequest: Boolean = true,
    ): HttpResponse {
        val response = request(path) {
            this.method = method
            accept(ContentType.Application.Json)
            if (token != null) bearerAuth(token)
            if (body != null) {
                contentType(contentType)
                setBody(body)
            }
        }
        withClue("$method $path returned an unexpected status") { response.status shouldBe expectedStatus }
        val responseBody = response.bodyAsText()
        val responseBuilder = SimpleResponse.Builder.status(response.status.value)
        response.headers.entries().forEach { (name, values) -> responseBuilder.withHeader(name, values) }
        if (responseBody.isNotEmpty()) responseBuilder.withBody(responseBody)
        val report = if (validateRequest) {
            val requestBuilder = SimpleRequest.Builder(method.value, path).withAccept("application/json")
            if (token != null) requestBuilder.withAuthorization("Bearer $token")
            if (body != null) requestBuilder.withContentType(contentType.toString()).withBody(body)
            validator.validate(requestBuilder.build(), responseBuilder.build())
        } else {
            // Malformed and unauthorized input is intentionally outside the valid request schema.
            validator.validateResponse(path, Request.Method.valueOf(method.value), responseBuilder.build())
        }
        withClue(buildString {
            append("Shared contract violation for $method $path -> ${response.status.value}: ")
            append(report.messages.joinToString { "${it.key}: ${it.message}" })
        }) { report.hasErrors() shouldBe false }
        return response
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-contract-test-")
        val storage = directory.resolve("questions.json")
        Files.writeString(storage, "[]")
        try {
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    quizModule(
                        ApiSettings(ADMIN_USERNAME, testPasswordHash(ADMIN_PASSWORD), JWT_SECRET, adminAllowedOrigins = listOf("http://localhost:5173")),
                        FileQuestionRepository(storage),
                    )
                }
                block()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        private const val ADMIN_USERNAME = "contract-test-admin"
        private const val ADMIN_PASSWORD = "contract-test-password-only"
        private const val JWT_SECRET = "contract-test-signing-key-with-at-least-32-bytes"
        private const val QUESTION_BODY = """{"text":"Which currency is used in Japan?","options":["JPY","EUR"],"correctAnswer":"JPY"}"""
        private const val INVALID_LOGIN_BODY = """{"username":"contract-test-admin","password":"incorrect-password"}"""
    }
}
