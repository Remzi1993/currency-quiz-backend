package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.*

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.*

class ServerTest {
    @Test
    fun `admin can manage questions while players can play without an account`() = withApi {
        assertEquals(JsonArray(emptyList()), client.get("/api/v1/questions").json())
        val token = client.login()
        val created = client.createQuestion(token)
        assertEquals(HttpStatusCode.Created, created.status)
        val question = created.json().jsonObject
        val id = question.getValue("id").jsonPrimitive.content
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals("/api/v1/questions/$id", created.headers[HttpHeaders.Location])
        assertEquals("JPY", question.getValue("correctAnswer").jsonPrimitive.content)

        val publicQuestion = client.get("/api/v1/questions/$id")
        assertEquals(HttpStatusCode.OK, publicQuestion.status)
        assertPublicQuestion(publicQuestion.json().jsonObject)
        val listing = client.get("/api/v1/questions").json().jsonArray
        assertEquals(1, listing.size)
        assertPublicQuestion(listing.single().jsonObject)

        for ((answer, correct) in listOf("  jpy  " to true, "EUR" to false)) {
            val result = client.post("/api/v1/questions/$id/answers") {
                jsonBody(buildJsonObject { put("answer", answer) }.toString())
            }
            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals(buildJsonObject { put("correct", correct) }, result.json())
        }
        val patched = client.patch("/api/v1/questions/$id") {
            bearerAuth(token)
            jsonBody("""{"text":"Which currency does Japan use?"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals("Which currency does Japan use?", patched.json().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(question.getValue("options"), patched.json().jsonObject.getValue("options"))
        assertEquals(question.getValue("correctAnswer"), patched.json().jsonObject.getValue("correctAnswer"))
        val deleted = client.delete("/api/v1/questions/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/questions/$id").status)
        assertEquals(JsonArray(emptyList()), client.get("/api/v1/questions").json())
    }

    @Test
    fun `first application startup serves the bundled quiz publicly without revealing answers`() {
        val directory = Files.createTempDirectory("currencyquiz-seeded-api-test-")
        val storage = directory.resolve("questions.json")
        val resource = assertNotNull(javaClass.getResourceAsStream("/questions-seed.json"))
        val seed = resource.bufferedReader().use { Json.decodeFromString<List<Question>>(it.readText()) }
        try {
            assertFalse(Files.exists(storage))
            runApi(storage, initializeEmpty = false) {
                val response = client.get("/api/v1/questions")
                assertEquals(HttpStatusCode.OK, response.status)
                val questions = response.json().jsonArray
                assertEquals(40, questions.size)
                assertEquals(seed.map { it.id }, questions.map { it.jsonObject.getValue("id").jsonPrimitive.content })
                questions.forEach { assertPublicQuestion(it.jsonObject) }
                val first = seed.first()
                val publicQuestion = client.get("/api/v1/questions/${first.id}")
                assertEquals(HttpStatusCode.OK, publicQuestion.status)
                assertPublicQuestion(publicQuestion.json().jsonObject)
                assertEquals(first.text, publicQuestion.json().jsonObject.getValue("text").jsonPrimitive.content)
                val incorrect = first.options.first { it != first.correctAnswer }
                for ((answer, correct) in listOf(first.correctAnswer to true, incorrect to false)) {
                    val result = client.post("/api/v1/questions/${first.id}/answers") {
                        jsonBody(buildJsonObject { put("answer", answer) }.toString())
                    }
                    assertEquals(HttpStatusCode.OK, result.status)
                    assertEquals(buildJsonObject { put("correct", correct) }, result.json())
                }
            }
            assertEquals(seed, Json.decodeFromString<List<Question>>(Files.readString(storage)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `every content mutation requires a bearer token`() = withApi {
        val id = UUID.randomUUID()
        val responses = listOf(
            client.createQuestion(),
            client.patch("/api/v1/questions/$id") { jsonBody("""{"text":"Updated?"}""") },
            client.delete("/api/v1/questions/$id"),
        )
        for (response in responses) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Bearer") == true)
            assertEquals("unauthorized", response.json().jsonObject.getValue("code").jsonPrimitive.content)
        }
    }

    @Test
    fun `login returns an expiring admin token and rejects invalid credentials`() = withApi {
        val login = client.post("/api/v1/auth/login") {
            jsonBody("""{"username":"$ADMIN_USERNAME","password":"$ADMIN_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val body = login.json().jsonObject
        assertEquals("Bearer", body.getValue("tokenType").jsonPrimitive.content)
        assertEquals(3600L, body.getValue("expiresIn").jsonPrimitive.long)
        val jwt = JWT.decode(body.getValue("accessToken").jsonPrimitive.content)
        assertEquals(ADMIN_USERNAME, jwt.subject)
        assertEquals("admin", jwt.getClaim("role").asString())
        assertNotNull(jwt.expiresAt)
        assertTrue(jwt.expiresAt.after(Date()))
        assertFalse(body.containsKey("password"))
        val badPassword = client.post("/api/v1/auth/login") {
            jsonBody("""{"username":"$ADMIN_USERNAME","password":"wrong-password"}""")
        }
        val badUsername = client.post("/api/v1/auth/login") {
            jsonBody("""{"username":"unknown","password":"$ADMIN_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, badPassword.status)
        assertEquals(HttpStatusCode.Unauthorized, badUsername.status)
        assertEquals(badPassword.json(), badUsername.json())
        assertFalse(badPassword.bodyAsText().contains(ADMIN_PASSWORD))
    }

    @Test
    fun `expired forged and non-admin tokens cannot mutate content`() = withApi {
        val invalidTokens = listOf(
            "not-a-jwt",
            signedToken(secret = "a-different-signing-secret-at-least-32-bytes"),
            signedToken(expiresAt = Instant.now().minusSeconds(60)),
            signedToken(role = "player"),
            signedToken(subject = "another-admin"),
            signedToken(issuer = "another-issuer"),
            signedToken(audience = "another-audience"),
            signedToken(expiresAt = null),
        )
        for (token in invalidTokens) assertEquals(HttpStatusCode.Unauthorized, client.createQuestion(token).status)
        assertEquals(JsonArray(emptyList()), client.get("/api/v1/questions").json())
    }

    @Test
    fun `invalid questions return client errors without creating content`() = withApi {
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
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(response.json().jsonObject.getValue("message").jsonPrimitive.content.isNotBlank())
        }
        assertEquals(JsonArray(emptyList()), client.get("/api/v1/questions").json())
    }

    @Test
    fun `malformed and incomplete JSON returns a safe client error`() = withApi {
        val token = client.login()
        for (body in listOf("{", "{}", "null", "[]", """{"text":42,"options":[],"correctAnswer":"JPY"}""")) {
            val response = client.post("/api/v1/questions") { bearerAuth(token); jsonBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(response.json().jsonObject.getValue("code").jsonPrimitive.content.isNotBlank())
            assertFalse(response.bodyAsText().contains("Exception"))
        }
    }

    @Test
    fun `patch validates the resulting question and preserves content when rejected`() = withApi {
        val token = client.login()
        val original = client.createQuestion(token).json().jsonObject
        val id = original.getValue("id").jsonPrimitive.content
        for (patch in listOf("{}", """{"text":" "}""", """{"options":["EUR","USD"]}""", """{"correctAnswer":"USD"}""")) {
            val response = client.patch("/api/v1/questions/$id") { bearerAuth(token); jsonBody(patch) }
            assertEquals(HttpStatusCode.BadRequest, response.status, patch)
            val unchanged = client.get("/api/v1/questions/$id").json().jsonObject
            assertEquals(original.getValue("text"), unchanged.getValue("text"))
            assertEquals(original.getValue("options"), unchanged.getValue("options"))
        }
        val updated = client.patch("/api/v1/questions/$id") {
            bearerAuth(token)
            jsonBody("""{"options":["EUR","USD"],"correctAnswer":"USD"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        for ((answer, correct) in listOf("JPY" to false, "USD" to true)) {
            val result = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"$answer"}""") }
            assertEquals(correct, result.json().jsonObject.getValue("correct").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `blank missing and oversized answers are invalid`() = withApi {
        val id = client.createQuestion(client.login()).json().jsonObject.getValue("id").jsonPrimitive.content
        for (body in listOf("{}", """{"answer":"  "}""", buildJsonObject { put("answer", "x".repeat(201)) }.toString())) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/questions/$id/answers") { jsonBody(body) }.status)
        }
    }

    @Test
    fun `malformed IDs and nonexistent questions produce distinct errors`() = withApi {
        val token = client.login()
        for ((id, status) in listOf("not-a-uuid" to HttpStatusCode.BadRequest, UUID.randomUUID().toString() to HttpStatusCode.NotFound)) {
            assertEquals(status, client.get("/api/v1/questions/$id").status)
            assertEquals(status, client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"JPY"}""") }.status)
            assertEquals(status, client.patch("/api/v1/questions/$id") { bearerAuth(token); jsonBody("""{"text":"New question?"}""") }.status)
            assertEquals(status, client.delete("/api/v1/questions/$id") { bearerAuth(token) }.status)
        }
        val unknownRoute = client.get("/api/v1/missing")
        assertEquals(HttpStatusCode.NotFound, unknownRoute.status)
        assertTrue(unknownRoute.json().jsonObject.containsKey("message"))
    }

    @Test
    fun `login rate limit returns Retry-After without blocking public reads`() = withApi {
        repeat(5) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/login") {
                jsonBody("""{"username":"$ADMIN_USERNAME","password":"incorrect"}""")
            }.status)
        }
        val throttled = client.post("/api/v1/auth/login") {
            jsonBody("""{"username":"$ADMIN_USERNAME","password":"incorrect"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
        assertTrue(throttled.json().jsonObject.containsKey("message"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/questions").status)
    }

    @Test
    fun `answer submissions have a separate rate limit`() = withApi {
        val token = client.login()
        val id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
        repeat(30) {
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"EUR"}""") }.status)
        }
        val throttled = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"EUR"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/questions").status)
        assertEquals(HttpStatusCode.Created, client.createQuestion(token).status)
    }

    @Test
    fun `CORS permits configured frontend JSON requests and rejects other origins`() = withApi {
        val preflight = client.options("/api/v1/questions") {
            header(HttpHeaders.Origin, FRONTEND_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "PATCH")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
        }
        assertTrue(preflight.status.isSuccess())
        assertEquals(FRONTEND_ORIGIN, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(preflight.headers[HttpHeaders.AccessControlAllowMethods]?.contains("PATCH") == true)
        val publicRead = client.get("/api/v1/questions") { header(HttpHeaders.Origin, FRONTEND_ORIGIN) }
        assertEquals(FRONTEND_ORIGIN, publicRead.headers[HttpHeaders.AccessControlAllowOrigin])
        val otherOrigin = client.get("/api/v1/questions") { header(HttpHeaders.Origin, "https://untrusted.example") }
        assertNull(otherOrigin.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `created and edited questions survive restart and deletions persist`() {
        val directory = Files.createTempDirectory("currencyquiz-persistence-test-")
        val storage = directory.resolve("questions.json")
        var id = ""
        try {
            runApi(storage) {
                val token = client.login()
                id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
                assertEquals(HttpStatusCode.OK, client.patch("/api/v1/questions/$id") {
                    bearerAuth(token)
                    jsonBody("""{"text":"Persisted question?"}""")
                }.status)
            }
            assertTrue(Files.exists(storage))
            runApi(storage) {
                val restored = client.get("/api/v1/questions/$id")
                assertEquals(HttpStatusCode.OK, restored.status)
                assertEquals("Persisted question?", restored.json().jsonObject.getValue("text").jsonPrimitive.content)
                val answer = client.post("/api/v1/questions/$id/answers") { jsonBody("""{"answer":"JPY"}""") }
                assertTrue(answer.json().jsonObject.getValue("correct").jsonPrimitive.boolean)
                val token = client.login()
                assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/questions/$id") { bearerAuth(token) }.status)
            }
            runApi(storage) { assertEquals(JsonArray(emptyList()), client.get("/api/v1/questions").json()) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `admin credentials and signing key cannot silently use insecure defaults`() {
        val required = listOf(
            "security.adminUsername" to ADMIN_USERNAME,
            "security.adminPassword" to ADMIN_PASSWORD,
            "security.jwtSecret" to JWT_SECRET,
        )
        for (missing in required) {
            val config = MapApplicationConfig(*required.filterNot { it.first == missing.first }.toTypedArray())
            assertFailsWith<IllegalStateException> { ApiSettings.from(config) }
        }
        val weakSecret = MapApplicationConfig(*required.map { if (it.first == "security.jwtSecret") it.first to "secret" else it }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { ApiSettings.from(weakSecret) }
    }

    @Test
    fun `Swagger UI links to the publicly served API specification`() = withApi {
        val response = client.get("/openapi")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val html = response.bodyAsText()
        assertContains(html, "SwaggerUIBundle")
        val specificationPath = Regex("""url:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        assertEquals("/openapi/documentation.yaml", specificationPath)
        val specification = client.get(assertNotNull(specificationPath))
        assertEquals(HttpStatusCode.OK, specification.status)
        val yaml = specification.bodyAsText()
        assertContains(yaml, "openapi:")
        assertContains(yaml, "title: Currency quiz API")
        assertContains(yaml, "/api/v1/questions:")
        assertContains(yaml, "/api/v1/auth/login:")
    }

    @Test
    fun `body endpoints reject non-JSON media types with a consistent JSON error`() = withApi {
        val token = client.login()
        val id = client.createQuestion(token).json().jsonObject.getValue("id").jsonPrimitive.content
        for (path in listOf("/api/v1/auth/login", "/api/v1/questions", "/api/v1/questions/$id/answers")) {
            val response = client.post(path) {
                bearerAuth(token)
                contentType(ContentType.Text.Plain)
                setBody("not JSON")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, path)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("unsupported_media_type", response.json().jsonObject.getValue("code").jsonPrimitive.content)
        }
    }

    @Test
    fun `PATCH treats null as omitted and rejects unknown fields without changing content`() = withApi {
        val token = client.login()
        val original = client.createQuestion(token).json().jsonObject
        val id = original.getValue("id").jsonPrimitive.content
        val patched = client.patch("/api/v1/questions/$id") {
            bearerAuth(token)
            jsonBody("""{"text":"Updated question?","options":null,"correctAnswer":null}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals("Updated question?", patched.json().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(original.getValue("options"), patched.json().jsonObject.getValue("options"))
        assertEquals(original.getValue("correctAnswer"), patched.json().jsonObject.getValue("correctAnswer"))
        for (body in listOf("""{"text":null,"options":null,"correctAnswer":null}""", """{"text":"Unexpected update?","unknown":true}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.patch("/api/v1/questions/$id") {
                bearerAuth(token)
                jsonBody(body)
            }.status)
        }
        assertEquals("Updated question?", client.get("/api/v1/questions/$id").json().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/questions/$id/answers") {
            jsonBody("""{"answer":"JPY","unknown":true}""")
        }.status)
    }

    @Test
    fun `unexpected repository failures return sanitized JSON without internal details`() = testApplication {
        environment { config = MapApplicationConfig() }
        val internalDetail = "internal-storage-password-do-not-return"
        val repository = object : QuestionRepository {
            override suspend fun all(): List<Question> = error(internalDetail)
            override suspend fun find(id: String): Question? = error(internalDetail)
            override suspend fun create(request: CreateQuestionRequest): Question = error(internalDetail)
            override suspend fun update(id: String, request: UpdateQuestionRequest): Question? = error(internalDetail)
            override suspend fun delete(id: String): Boolean = error(internalDetail)
        }
        application { quizModule(ApiSettings(ADMIN_USERNAME, ADMIN_PASSWORD, JWT_SECRET), repository) }
        val response = client.get("/api/v1/questions")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("internal_error", response.json().jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("An unexpected error occurred", response.json().jsonObject.getValue("message").jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains(internalDetail))
        assertFalse(response.bodyAsText().contains("IllegalStateException"))
    }

    private fun assertPublicQuestion(question: JsonObject) = assertEquals(setOf("id", "text", "options"), question.keys)

    private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-api-test-")
        try {
            runApi(directory.resolve("questions.json"), block = block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runApi(
        storage: Path,
        initializeEmpty: Boolean = true,
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
                    "security.adminPassword" to ADMIN_PASSWORD,
                    "security.jwtSecret" to JWT_SECRET,
                    "storage.questionsFile" to storage.toString(),
                    "cors.allowedOrigins" to FRONTEND_ORIGIN,
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
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
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
        issuer: String = "currencyquiz-api",
        audience: String = "currencyquiz-admin",
        expiresAt: Instant? = Instant.now().plusSeconds(3600),
    ): String {
        val token = JWT.create().withSubject(subject).withClaim("role", role)
            .withIssuer(issuer).withAudience(audience)
            .withIssuedAt(Date.from(Instant.now().minusSeconds(120)))
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
