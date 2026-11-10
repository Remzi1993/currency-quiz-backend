package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import info.remzi.currencyquiz.contract.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.*

class ServerTest : FunSpec() {
    init {
        test("admin can manage questions while players can play without an account") {
            withApi {
                client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList())
                val token = client.login()
                val created = client.createQuestion(token)
                created.status shouldBe HttpStatusCode.Created
                val question = created.json().jsonObject
                val id = question.getValue("id").jsonPrimitive.content
                UUID.fromString(id).toString() shouldBe id
                created.headers[HttpHeaders.Location] shouldBe "/api/v1/questions/$id"
                question.getValue("correctAnswer").jsonPrimitive.content shouldBe "JPY"

                val publicQuestion = client.get("/api/v1/questions/$id")
                publicQuestion.status shouldBe HttpStatusCode.OK
                assertPublicQuestion(publicQuestion.json().jsonObject)
                val listing = client.get("/api/v1/questions").json().jsonArray
                listing.size shouldBe 1
                assertPublicQuestion(listing.single().jsonObject)

                for ((answer, correct) in listOf("  jpy  " to true, "EUR" to false)) {
                    val result = client.post("/api/v1/questions/$id/answers") {
                        jsonBody(buildJsonObject { put("answer", answer) }.toString())
                    }
                    result.status shouldBe HttpStatusCode.OK
                    result.json() shouldBe buildJsonObject { put("correct", correct) }
                }
                val patched = client.patch("/api/v1/questions/$id") {
                    bearerAuth(token)
                    jsonBody("""{"text":"Which currency does Japan use?"}""")
                }
                patched.status shouldBe HttpStatusCode.OK
                patched.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Which currency does Japan use?"
                patched.json().jsonObject.getValue("options") shouldBe question.getValue("options")
                patched.json().jsonObject.getValue("correctAnswer") shouldBe question.getValue("correctAnswer")
                val deleted = client.delete("/api/v1/questions/$id") { bearerAuth(token) }
                deleted.status shouldBe HttpStatusCode.NoContent
                deleted.bodyAsText() shouldBe ""
                client.get("/api/v1/questions/$id").status shouldBe HttpStatusCode.NotFound
                client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList())
            }
        }

        test("first application startup serves the bundled quiz publicly without revealing answers") {
            val directory = Files.createTempDirectory("currencyquiz-seeded-api-test-")
            val storage = directory.resolve("questions.json")
            val resource = javaClass.getResourceAsStream("/questions-seed.json").shouldNotBeNull()
            val seed = resource.bufferedReader().use { Json.decodeFromString<List<Question>>(it.readText()) }
            try {
                Files.exists(storage) shouldBe false
                runApi(storage, initializeEmpty = false) {
                    val response = client.get("/api/v1/questions")
                    response.status shouldBe HttpStatusCode.OK
                    val questions = response.json().jsonArray
                    questions.size shouldBe 40
                    questions.map { it.jsonObject.getValue("id").jsonPrimitive.content } shouldBe seed.map { it.id }
                    questions.forEach { assertPublicQuestion(it.jsonObject) }
                    val first = seed.first()
                    val publicQuestion = client.get("/api/v1/questions/${first.id}")
                    publicQuestion.status shouldBe HttpStatusCode.OK
                    assertPublicQuestion(publicQuestion.json().jsonObject)
                    publicQuestion.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe first.text
                    val incorrect = first.options.first { it != first.correctAnswer }
                    for ((answer, correct) in listOf(first.correctAnswer to true, incorrect to false)) {
                        val result = client.post("/api/v1/questions/${first.id}/answers") {
                            jsonBody(buildJsonObject { put("answer", answer) }.toString())
                        }
                        result.status shouldBe HttpStatusCode.OK
                        result.json() shouldBe buildJsonObject { put("correct", correct) }
                    }
                }
                (Json.decodeFromString<List<Question>>(Files.readString(storage))) shouldBe seed
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        test("every content mutation requires a bearer token") {
            withApi {
                val id = UUID.randomUUID()
                val responses = listOf(
                    client.createQuestion(),
                    client.patch("/api/v1/questions/$id") { jsonBody("""{"text":"Updated?"}""") },
                    client.delete("/api/v1/questions/$id"),
                )
                for (response in responses) {
                    response.status shouldBe HttpStatusCode.Unauthorized
                    (response.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true) shouldBe true
                    response.json().jsonObject.getValue("code").jsonPrimitive.content shouldBe "unauthorized"
                }
            }
        }

        test("login returns an expiring admin token and rejects invalid credentials") {
            withApi {
                val login = client.post("/api/v1/auth/login") {
                    jsonBody("""{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""")
                }
                login.status shouldBe HttpStatusCode.OK
                val body = login.json().jsonObject
                body.getValue("tokenType").jsonPrimitive.content shouldBe "Bearer"
                body.getValue("expiresIn").jsonPrimitive.long shouldBe 300L
                val jwt = JWT.decode(body.getValue("accessToken").jsonPrimitive.content)
                jwt.subject shouldBe ADMIN_USERNAME
                jwt.getClaim("role").asString() shouldBe "admin"
                jwt.expiresAt.shouldNotBeNull()
                jwt.expiresAt.after(Date()) shouldBe true
                body.containsKey("password") shouldBe false
                val badPassword = client.post("/api/v1/auth/login") {
                    jsonBody("""{"username":"$ADMIN_USERNAME","password":"wrong-password"}""")
                }
                val badUsername = client.post("/api/v1/auth/login") {
                    jsonBody("""{"username":"unknown","password":"$ADMIN_PASSWORD"}""")
                }
                badPassword.status shouldBe HttpStatusCode.Unauthorized
                badUsername.status shouldBe HttpStatusCode.Unauthorized
                badUsername.json() shouldBe badPassword.json()
                badPassword.bodyAsText().contains(ADMIN_PASSWORD) shouldBe false
            }
        }

        test("expired forged and non-admin tokens cannot mutate content") {
            withApi {
                val invalidTokens = listOf(
                    "not-a-jwt",
                    signedToken(secret = "a-different-signing-secret-at-least-32-bytes"),
                    signedToken(expiresAt = Instant.now().minusSeconds(60)),
                    signedToken(role = "player"),
                    signedToken(subject = "another-admin"),
                    signedToken(issuer = "another-issuer"),
                    signedToken(audience = "another-audience"),
                    signedToken(expiresAt = null),
                    signedToken(includeLoginContext = false),
                )
                for (token in invalidTokens) client.createQuestion(token).status shouldBe HttpStatusCode.Unauthorized
                client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList())
                client.createQuestion(signedToken()).status shouldBe HttpStatusCode.Created
            }
        }

        test("invalid questions return client errors without creating content") {
            withApi {
                val token = client.login()
                val bodies = listOf(
                    """{"text":" ","options":["JPY","EUR"],"correctAnswer":"JPY"}""",
                    """{"text":"Currency?","options":["JPY"],"correctAnswer":"JPY"}""",
                    """{"text":"Currency?","options":["JPY"," jpy "],"correctAnswer":"JPY"}""",
                    """{"text":"Currency?","options":["JPY"," "],"correctAnswer":"JPY"}""",
                    """{"text":"Currency?","options":["JPY","EUR"],"correctAnswer":"USD"}""",
                    """{"text":"Currency?","options":["JPY","EUR"],"correctAnswer":" "}""",
                    questionBody(text = "x".repeat(501)),
                    questionBody(options = (1..11).map { "Option $it" }, correctAnswer = "Option 1"),
                    questionBody(options = listOf("JPY", "x".repeat(201))),
                )
                for (body in bodies) {
                    val response = client.post("/api/v1/questions") { bearerAuth(token); jsonBody(body) }
                    withClue(body) { response.status shouldBe HttpStatusCode.BadRequest }
                    response.json().jsonObject.getValue("message").jsonPrimitive.content.isNotBlank() shouldBe true
                }
                client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList())
            }
        }

        test("malformed and incomplete JSON returns a safe client error") {
            withApi {
                val token = client.login()
                for (body in listOf("{", "{}", "null", "[]", """{"text":42,"options":[],"correctAnswer":"JPY"}""")) {
                    val response = client.post("/api/v1/questions") { bearerAuth(token); jsonBody(body) }
                    withClue(body) { response.status shouldBe HttpStatusCode.BadRequest }
                    response.json().jsonObject.getValue("code").jsonPrimitive.content.isNotBlank() shouldBe true
                    response.bodyAsText().contains("Exception") shouldBe false
                }
            }
        }

        test("patch validates the resulting question and preserves content when rejected") {
            withApi {
                val token = client.login()
                val original = client.createQuestion(token).json().jsonObject
                val id = original.getValue("id").jsonPrimitive.content
                for (patch in listOf("{}", """{"text":" "}""", """{"options":["EUR","USD"]}""", """{"correctAnswer":"USD"}""")) {
                    val response = client.patch("/api/v1/questions/$id") { bearerAuth(token); jsonBody(patch) }
                    withClue(patch) { response.status shouldBe HttpStatusCode.BadRequest }
                    val unchanged = client.get("/api/v1/questions/$id").json().jsonObject
                    unchanged.getValue("text") shouldBe original.getValue("text")
                    unchanged.getValue("options") shouldBe original.getValue("options")
                }
                val updated = client.patch("/api/v1/questions/$id") {
                    bearerAuth(token)
                    jsonBody("""{"options":["EUR","USD"],"correctAnswer":"USD"}""")
                }
                updated.status shouldBe HttpStatusCode.OK
                for ((answer, correct) in listOf("JPY" to false, "USD" to true)) {
                    val result = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"$answer"}""") }
                    result.json().jsonObject.getValue("correct").jsonPrimitive.boolean shouldBe correct
                }
            }
        }

        test("blank missing and oversized answers are invalid") {
            withApi {
                val id = client.createQuestion(client.login()).json().jsonObject.getValue("id").jsonPrimitive.content
                for (body in listOf("{}", """{"answer":"  "}""", buildJsonObject { put("answer", "x".repeat(201)) }.toString())) {
                    client.post("/api/v1/questions/$id/answers") { jsonBody(body) }.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("malformed IDs and nonexistent questions produce distinct errors") {
            withApi {
                val token = client.login()
                for ((id, status) in listOf("not-a-uuid" to HttpStatusCode.BadRequest, UUID.randomUUID().toString() to HttpStatusCode.NotFound)) {
                    client.get("/api/v1/questions/$id").status shouldBe status
                    client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"JPY"}""") }.status shouldBe status
                    client.patch("/api/v1/questions/$id") { bearerAuth(token); jsonBody("""{"text":"New question?"}""") }.status shouldBe status
                    client.delete("/api/v1/questions/$id") { bearerAuth(token) }.status shouldBe status
                }
                val unknownRoute = client.get("/api/v1/missing")
                unknownRoute.status shouldBe HttpStatusCode.NotFound
                unknownRoute.json().jsonObject.containsKey("message") shouldBe true
            }
        }

        test("login rate limit returns Retry-After without blocking public reads") {
            withApi {
                repeat(5) {
                    client.post("/api/v1/auth/login") {
                        jsonBody("""{"username":"$ADMIN_USERNAME","password":"incorrect"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
                val throttled = client.post("/api/v1/auth/login") {
                    jsonBody("""{"username":"$ADMIN_USERNAME","password":"incorrect"}""")
                }
                throttled.status shouldBe HttpStatusCode.TooManyRequests
                throttled.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                throttled.json().jsonObject.containsKey("message") shouldBe true
                client.get("/api/v1/questions").status shouldBe HttpStatusCode.OK
            }
        }

        test("answer submissions have a separate rate limit") {
            withApi {
                val token = client.login()
                val id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
                repeat(30) {
                    client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"EUR"}""") }.status shouldBe HttpStatusCode.OK
                }
                val throttled = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"EUR"}""") }
                throttled.status shouldBe HttpStatusCode.TooManyRequests
                throttled.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                client.get("/api/v1/questions").status shouldBe HttpStatusCode.OK
                client.createQuestion(token).status shouldBe HttpStatusCode.Created
            }
        }

        test("CORS permits configured frontend JSON requests and rejects other origins") {
            withApi {
                val preflight = client.options("/api/v1/questions") {
                    header(HttpHeaders.Origin, FRONTEND_ORIGIN)
                    header(HttpHeaders.AccessControlRequestMethod, "PATCH")
                    header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
                }
                preflight.status.isSuccess() shouldBe true
                preflight.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe FRONTEND_ORIGIN
                (preflight.headers[HttpHeaders.AccessControlAllowMethods]?.contains("PATCH") == true) shouldBe true
                val publicRead = client.get("/api/v1/questions") { header(HttpHeaders.Origin, FRONTEND_ORIGIN) }
                publicRead.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe FRONTEND_ORIGIN
                val otherOrigin = client.get("/api/v1/questions") { header(HttpHeaders.Origin, "https://untrusted.example") }
                otherOrigin.headers[HttpHeaders.AccessControlAllowOrigin].shouldBeNull()
            }
        }

        test("wildcard CORS permits arbitrary frontend origins without credentials") {
            for (allowedOrigins in listOf("*", "$FRONTEND_ORIGIN,*")) {
                withApi(allowedOrigins = allowedOrigins) {
                    for (origin in listOf("https://quiz.school.example", "http://localhost:4321", "https://another-frontend.example:8443")) {
                        val response = client.get("/api/v1/questions") { header(HttpHeaders.Origin, origin) }
                        response.status shouldBe HttpStatusCode.OK
                        assertWildcardCors(response)
                        response.json() shouldBe JsonArray(emptyList())
                    }
                }
            }
        }

        test("wildcard CORS permits JSON and bearer authorization preflights for supported methods") {
            withApi(allowedOrigins = "*") {
                val id = UUID.randomUUID()
                val requests = listOf(
                    Triple("/api/v1/auth/login", "POST", "content-type"),
                    Triple("/api/v1/questions/$id/answers", "POST", "content-type"),
                    Triple("/api/v1/questions", "POST", "authorization,content-type"),
                    Triple("/api/v1/questions/$id", "PATCH", "authorization,content-type"),
                    Triple("/api/v1/questions/$id", "DELETE", "authorization"),
                )
                for ((path, method, requestedHeaders) in requests) {
                    val response = client.options(path) {
                        header(HttpHeaders.Origin, "https://any-frontend.example")
                        header(HttpHeaders.AccessControlRequestMethod, method)
                        header(HttpHeaders.AccessControlRequestHeaders, requestedHeaders)
                    }
                    withClue("$method $path") { response.status.isSuccess() shouldBe true }
                    assertWildcardCors(response)
                    val methods = response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().split(',').map(String::trim)
                    methods.shouldContain(method)
                    val headers = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty()
                        .split(',').map { it.trim().lowercase() }
                    for (requestedHeader in requestedHeaders.split(',')) headers.shouldContain(requestedHeader)
                }
            }
        }

        test("wildcard CORS preserves bearer protection and authenticated question management") {
            withApi(allowedOrigins = "*") {
                val origin = "https://any-admin-frontend.example"
                val missingId = UUID.randomUUID()
                val unauthorized = listOf(
                    client.post("/api/v1/questions") {
                        header(HttpHeaders.Origin, origin)
                        jsonBody(questionBody())
                    },
                    client.patch("/api/v1/questions/$missingId") {
                        header(HttpHeaders.Origin, origin)
                        jsonBody("""{"text":"Updated?"}""")
                    },
                    client.delete("/api/v1/questions/$missingId") { header(HttpHeaders.Origin, origin) },
                )
                for (response in unauthorized) {
                    response.status shouldBe HttpStatusCode.Unauthorized
                    (response.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true) shouldBe true
                    assertWildcardCors(response)
                }

                val login = client.post("/api/v1/auth/login") {
                    header(HttpHeaders.Origin, origin)
                    jsonBody("""{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""")
                }
                login.status shouldBe HttpStatusCode.OK
                assertWildcardCors(login)
                val token = login.json().jsonObject.getValue("accessToken").jsonPrimitive.content
                val created = client.post("/api/v1/questions") {
                    header(HttpHeaders.Origin, origin)
                    bearerAuth(token)
                    jsonBody(questionBody())
                }
                created.status shouldBe HttpStatusCode.Created
                assertWildcardCors(created)
                val id = created.json().jsonObject.getValue("id").jsonPrimitive.content

                val updated = client.patch("/api/v1/questions/$id") {
                    header(HttpHeaders.Origin, origin)
                    bearerAuth(token)
                    jsonBody("""{"text":"Updated from another origin?"}""")
                }
                updated.status shouldBe HttpStatusCode.OK
                assertWildcardCors(updated)
                updated.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Updated from another origin?"
                val deleted = client.delete("/api/v1/questions/$id") {
                    header(HttpHeaders.Origin, origin)
                    bearerAuth(token)
                }
                deleted.status shouldBe HttpStatusCode.NoContent
                assertWildcardCors(deleted)
                client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList())
            }
        }

        test("wildcard origins cannot bypass the answer rate limit") {
            withApi(allowedOrigins = "*") {
                val id = client.createQuestion(client.login()).json().jsonObject.getValue("id").jsonPrimitive.content
                repeat(30) { index ->
                    val response = client.post("/api/v1/questions/$id/answers") {
                        header(HttpHeaders.Origin, "https://frontend-$index.example")
                        jsonBody("""{"answer":"EUR"}""")
                    }
                    response.status shouldBe HttpStatusCode.OK
                    assertWildcardCors(response)
                }
                val throttled = client.post("/api/v1/questions/$id/answers") {
                    header(HttpHeaders.Origin, "https://one-more-frontend.example")
                    jsonBody("""{"answer":"EUR"}""")
                }
                throttled.status shouldBe HttpStatusCode.TooManyRequests
                throttled.headers[HttpHeaders.RetryAfter].shouldNotBeNull()
                assertWildcardCors(throttled)
            }
        }

        test("created and edited questions survive restart and deletions persist") {
            val directory = Files.createTempDirectory("currencyquiz-persistence-test-")
            val storage = directory.resolve("questions.json")
            var id = ""
            try {
                runApi(storage) {
                    val token = client.login()
                    id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
                    client.patch("/api/v1/questions/$id") {
                        bearerAuth(token)
                        jsonBody("""{"text":"Persisted question?"}""")
                    }.status shouldBe HttpStatusCode.OK
                }
                Files.exists(storage) shouldBe true
                runApi(storage) {
                    val restored = client.get("/api/v1/questions/$id")
                    restored.status shouldBe HttpStatusCode.OK
                    restored.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Persisted question?"
                    val answer = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"JPY"}""") }
                    answer.json().jsonObject.getValue("correct").jsonPrimitive.boolean shouldBe true
                    val token = client.login()
                    client.delete("/api/v1/questions/$id") { bearerAuth(token) }.status shouldBe HttpStatusCode.NoContent
                }
                runApi(storage) { client.get("/api/v1/questions").json() shouldBe JsonArray(emptyList()) }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        test("admin credentials and signing key cannot silently use insecure defaults") {
            val required = listOf(
                "security.adminUsername" to ADMIN_USERNAME,
                "security.adminPasswordHash" to testPasswordHash(ADMIN_PASSWORD),
                "security.jwtSecret" to JWT_SECRET,
            )
            for (missing in required) {
                val config = MapApplicationConfig(*required.filterNot { it.first == missing.first }.toTypedArray())
                shouldThrow<IllegalStateException> { ApiSettings.from(config) }
            }
            val weakSecret = MapApplicationConfig(*required.map { if (it.first == "security.jwtSecret") it.first to "secret" else it }.toTypedArray())
            shouldThrow<IllegalArgumentException> { ApiSettings.from(weakSecret) }
        }

        test("Swagger UI links to the publicly served API specification") {
            withApi {
                val response = client.get("/openapi")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Text.Html
                val html = response.bodyAsText()
                html.shouldContain("SwaggerUIBundle")
                val specificationPath = Regex("""url:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
                specificationPath shouldBe "/openapi/documentation.yaml"
                val specification = client.get(specificationPath.shouldNotBeNull())
                specification.status shouldBe HttpStatusCode.OK
                val yaml = specification.bodyAsText()
                yaml.shouldContain("openapi:")
                yaml.shouldContain("title: Currency quiz API")
                yaml.shouldContain("/api/v1/questions:")
                yaml.shouldContain("/api/v1/auth/login:")
            }
        }

        test("body endpoints reject non-JSON media types with a consistent JSON error") {
            withApi {
                val token = client.login()
                val id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
                for (path in listOf("/api/v1/auth/login", "/api/v1/questions", "/api/v1/questions/$id/answers")) {
                    val response = client.post(path) {
                        bearerAuth(token)
                        contentType(ContentType.Text.Plain)
                        setBody("not JSON")
                    }
                    withClue(path) { response.status shouldBe HttpStatusCode.UnsupportedMediaType }
                    response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                    response.json().jsonObject.getValue("code").jsonPrimitive.content shouldBe "unsupported_media_type"
                }
            }
        }

        test("PATCH treats null as omitted and rejects unknown fields without changing content") {
            withApi {
                val token = client.login()
                val original = client.createQuestion(token).json().jsonObject
                val id = original.getValue("id").jsonPrimitive.content
                val patched = client.patch("/api/v1/questions/$id") {
                    bearerAuth(token)
                    jsonBody("""{"text":"Updated question?","options":null,"correctAnswer":null}""")
                }
                patched.status shouldBe HttpStatusCode.OK
                patched.json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Updated question?"
                patched.json().jsonObject.getValue("options") shouldBe original.getValue("options")
                patched.json().jsonObject.getValue("correctAnswer") shouldBe original.getValue("correctAnswer")
                for (body in listOf("""{"text":null,"options":null,"correctAnswer":null}""", """{"text":"Unexpected update?","unknown":true}""")) {
                    client.patch("/api/v1/questions/$id") {
                        bearerAuth(token)
                        jsonBody(body)
                    }.status shouldBe HttpStatusCode.BadRequest
                }
                client.get("/api/v1/questions/$id").json().jsonObject.getValue("text").jsonPrimitive.content shouldBe "Updated question?"
                client.post("/api/v1/questions/$id/answers") {
                    jsonBody("""{"answer":"JPY","unknown":true}""")
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("unexpected repository failures return sanitized JSON without internal details") {
            testApplication {
                environment { config = MapApplicationConfig() }
                val internalDetail = "internal-storage-password-do-not-return"
                val repository = object : QuestionRepository {
                    override suspend fun all(): List<Question> = error(internalDetail)
                    override suspend fun find(id: String): Question? = error(internalDetail)
                    override suspend fun create(request: CreateQuestionRequest): Question = error(internalDetail)
                    override suspend fun update(id: String, request: UpdateQuestionRequest): Question? = error(internalDetail)
                    override suspend fun delete(id: String): Boolean = error(internalDetail)
                }
                application { quizModule(ApiSettings(ADMIN_USERNAME, testPasswordHash(ADMIN_PASSWORD), JWT_SECRET), repository) }
                val response = client.get("/api/v1/questions")
                response.status shouldBe HttpStatusCode.InternalServerError
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                response.json().jsonObject.getValue("code").jsonPrimitive.content shouldBe "internal_error"
                response.json().jsonObject.getValue("message").jsonPrimitive.content shouldBe "An unexpected error occurred"
                response.bodyAsText().contains(internalDetail) shouldBe false
                response.bodyAsText().contains("IllegalStateException") shouldBe false
            }
        }
    }

    private fun assertPublicQuestion(question: JsonObject) = question.keys shouldBe setOf("id", "text", "options")

    private fun assertWildcardCors(response: HttpResponse) {
        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "*"
        response.headers[HttpHeaders.AccessControlAllowCredentials].shouldBeNull()
    }

    private fun withApi(allowedOrigins: String = FRONTEND_ORIGIN, block: suspend ApplicationTestBuilder.() -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-api-test-")
        try {
            runApi(directory.resolve("questions.json"), allowedOrigins = allowedOrigins, block = block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runApi(
        storage: Path,
        initializeEmpty: Boolean = true,
        allowedOrigins: String = FRONTEND_ORIGIN,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        if (initializeEmpty && !Files.exists(storage)) {
            Files.createDirectories(storage.toAbsolutePath().parent)
            Files.writeString(storage, "[]")
        }
        testApplication {
            environment {
                config = MapApplicationConfig(
                    "security.adminUsername" to ADMIN_USERNAME,
                    "security.adminPasswordHash" to testPasswordHash(ADMIN_PASSWORD),
                    "security.jwtSecret" to JWT_SECRET,
                    "storage.questionsFile" to storage.toString(),
                    "cors.allowedOrigins" to allowedOrigins,
                )
            }
            application { module() }
            block()
        }
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.login(): String {
        val response = post("/api/v1/auth/login") {
            jsonBody("""{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""")
        }
        withClue(response.bodyAsText()) { response.status shouldBe HttpStatusCode.OK }
        return response.json().jsonObject.getValue("accessToken").jsonPrimitive.content
    }

    private suspend fun HttpClient.createQuestion(token: String? = null): HttpResponse = post("/api/v1/questions") {
        if (token != null) bearerAuth(token)
        jsonBody(questionBody())
    }

    private fun questionBody(
        text: String = "What is the currency of Japan?",
        options: List<String> = listOf("JPY", "EUR"),
        correctAnswer: String = "JPY",
    ): String = buildJsonObject {
        put("text", text)
        putJsonArray("options") { options.forEach { add(it) } }
        put("correctAnswer", correctAnswer)
    }.toString()

    private fun signedToken(
        secret: String = JWT_SECRET,
        subject: String = ADMIN_USERNAME,
        role: String = "admin",
        issuer: String = "currency-quiz-api",
        audience: String = "currency-quiz-admin",
        expiresAt: Instant? = Instant.now().plusSeconds(120),
        includeLoginContext: Boolean = true,
    ): String {
        val issuedAt = Instant.now().minusSeconds(120)
        val token = JWT.create().withHeader(mapOf("typ" to "at+jwt"))
            .withSubject(subject).withClaim("role", role)
            .withClaim("token_use", "access").withClaim("csrf", "c".repeat(43))
            .withIssuer(issuer).withAudience(audience)
            .withIssuedAt(Date.from(issuedAt))
        if (includeLoginContext) {
            token.withClaim("auth_time", issuedAt.epochSecond)
                .withClaim("absolute_exp", issuedAt.plusSeconds(86_400).epochSecond)
        }
        if (expiresAt != null) token.withExpiresAt(Date.from(expiresAt))
        return token.sign(Algorithm.HMAC256(secret))
    }

    companion object {
        private const val ADMIN_USERNAME = "test-admin"
        private const val ADMIN_PASSWORD = "test-admin-password-123"
        private const val JWT_SECRET = "test-signing-key-with-at-least-thirty-two-bytes"
        private const val FRONTEND_ORIGIN = "http://localhost:5173"
    }
}
