package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.CreateQuestionRequest
import info.remzi.currencyquiz.contract.UpdateQuestionRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*

/** Component tests exercise the real HTTP pipeline against a strict, per-test repository mock. */
class RoutingTest : FunSpec({
    test("public listing projects every repository question without answer fields") {
        withRepository { repository ->
            val second = ROUTE_QUESTION.copy(id = SECOND_ID, text = "Which currency does Europe use?", correctAnswer = "EUR")
            coEvery { repository.all() } returns listOf(ROUTE_QUESTION, second)

            val response = client.get("/api/v1/questions")

            response.status shouldBe HttpStatusCode.OK
            response.json() shouldBe JsonArray(listOf(publicQuestionJson(ROUTE_QUESTION), publicQuestionJson(second)))
            coVerify(exactly = 1) { repository.all() }
            confirmVerified(repository)
        }
    }

    test("single-question lookup canonicalizes an uppercase UUID before delegating") {
        withRepository { repository ->
            coEvery { repository.find(ROUTE_ID) } returns ROUTE_QUESTION

            val response = client.get("/api/v1/questions/${ROUTE_ID.uppercase()}")

            response.status shouldBe HttpStatusCode.OK
            response.json() shouldBe publicQuestionJson(ROUTE_QUESTION)
            coVerify(exactly = 1) { repository.find(ROUTE_ID) }
            confirmVerified(repository)
        }
    }

    context("answer comparison") {
        withTests(mapOf(
            "exact match" to ("JPY" to true),
            "case and whitespace normalized" to (" \tjPy\n" to true),
            "other listed option" to ("EUR" to false),
            "unlisted answer" to ("USD" to false),
        )) { (answer, correct) ->
            withRepository { repository ->
                coEvery { repository.find(ROUTE_ID) } returns ROUTE_QUESTION.copy(correctAnswer = " jPy ")

                val response = client.post("/api/v1/questions/$ROUTE_ID/answers") {
                    jsonBody(buildJsonObject { put("answer", answer) }.toString())
                }

                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.json() shouldBe buildJsonObject { put("correct", correct) }
                coVerify(exactly = 1) { repository.find(ROUTE_ID) }
                confirmVerified(repository)
            }
        }
    }

    test("create delegates the decoded request once and returns the repository result with Location") {
        withRepository { repository ->
            val captured = slot<CreateQuestionRequest>()
            coEvery { repository.create(capture(captured)) } returns ROUTE_QUESTION

            val response = client.post("/api/v1/questions") {
                bearerAuth(routeToken())
                jsonBody("""{"text":"  Japan?  ","options":[" jpy ","EUR"],"correctAnswer":"JPY"}""")
            }

            response.status shouldBe HttpStatusCode.Created
            response.headers[HttpHeaders.Location] shouldBe "/api/v1/questions/$ROUTE_ID"
            response.json() shouldBe adminQuestionJson(ROUTE_QUESTION)
            captured.captured.text shouldBe "  Japan?  "
            captured.captured.options.toList() shouldBe listOf(" jpy ", "EUR")
            captured.captured.correctAnswer shouldBe "JPY"
            coVerify(exactly = 1) { repository.create(any()) }
            confirmVerified(repository)
        }
    }

    test("patch delegates only supplied fields and preserves explicit null as omitted") {
        withRepository { repository ->
            val captured = slot<UpdateQuestionRequest>()
            coEvery { repository.update(ROUTE_ID, capture(captured)) } returns ROUTE_QUESTION

            val response = client.patch("/api/v1/questions/${ROUTE_ID.uppercase()}") {
                bearerAuth(routeToken())
                jsonBody("""{"text":"Updated question","options":null,"correctAnswer":null}""")
            }

            response.status shouldBe HttpStatusCode.OK
            response.json() shouldBe adminQuestionJson(ROUTE_QUESTION)
            captured.captured.text shouldBe "Updated question"
            captured.captured.options shouldBe null
            captured.captured.correctAnswer shouldBe null
            coVerify(exactly = 1) { repository.update(ROUTE_ID, any()) }
            confirmVerified(repository)
        }
    }

    test("delete delegates exactly once and returns an empty 204 response") {
        withRepository { repository ->
            coEvery { repository.delete(ROUTE_ID) } returns true

            val response = client.delete("/api/v1/questions/${ROUTE_ID.uppercase()}") { bearerAuth(routeToken()) }

            response.status shouldBe HttpStatusCode.NoContent
            response.bodyAsText() shouldBe ""
            coVerify(exactly = 1) { repository.delete(ROUTE_ID) }
            confirmVerified(repository)
        }
    }

    context("missing questions") {
        withTests(mapOf(
            "public lookup" to HttpMethod.Get,
            "answer submission" to HttpMethod.Post,
            "admin patch" to HttpMethod.Patch,
            "admin delete" to HttpMethod.Delete,
        )) { method ->
            withRepository { repository ->
                when (method) {
                    HttpMethod.Patch -> coEvery { repository.update(ROUTE_ID, any()) } returns null
                    HttpMethod.Delete -> coEvery { repository.delete(ROUTE_ID) } returns false
                    else -> coEvery { repository.find(ROUTE_ID) } returns null
                }
                val response = client.request("/api/v1/questions/$ROUTE_ID${if (method == HttpMethod.Post) "/answers" else ""}") {
                    this.method = method
                    bearerAuth(routeToken())
                    if (method == HttpMethod.Post) jsonBody("""{"answer":"JPY"}""")
                    if (method == HttpMethod.Patch) jsonBody("""{"text":"Updated question"}""")
                }

                response.status shouldBe HttpStatusCode.NotFound
                response.errorCode() shouldBe "not_found"
                when (method) {
                    HttpMethod.Patch -> coVerify(exactly = 1) { repository.update(ROUTE_ID, any()) }
                    HttpMethod.Delete -> coVerify(exactly = 1) { repository.delete(ROUTE_ID) }
                    else -> coVerify(exactly = 1) { repository.find(ROUTE_ID) }
                }
                confirmVerified(repository)
            }
        }
    }

    context("rejected requests never reach storage") {
        withTests(
            nameFn = { it.name },
            RejectedRequest("create without credentials", HttpMethod.Post, "/api/v1/questions", VALID_QUESTION_BODY, authenticated = false, status = HttpStatusCode.Unauthorized),
            RejectedRequest("patch without credentials", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"text":"Updated"}""", authenticated = false, status = HttpStatusCode.Unauthorized),
            RejectedRequest("delete without credentials", HttpMethod.Delete, "/api/v1/questions/$ROUTE_ID", authenticated = false, status = HttpStatusCode.Unauthorized),
            RejectedRequest("malformed public ID", HttpMethod.Get, "/api/v1/questions/1-1-1-1-1"),
            RejectedRequest("malformed answer ID", HttpMethod.Post, "/api/v1/questions/not-a-uuid/answers", """{"answer":"JPY"}"""),
            RejectedRequest("malformed patch ID", HttpMethod.Patch, "/api/v1/questions/not-a-uuid", """{"text":"Updated"}"""),
            RejectedRequest("malformed delete ID", HttpMethod.Delete, "/api/v1/questions/not-a-uuid"),
            RejectedRequest("empty patch", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", "{}"),
            RejectedRequest("all-null patch", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"text":null,"options":null,"correctAnswer":null}"""),
            RejectedRequest("blank patch text", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"text":" "}"""),
            RejectedRequest("oversized patch text", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", buildJsonObject { put("text", "x".repeat(501)) }.toString()),
            RejectedRequest("too few patch options", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"options":["JPY"]}"""),
            RejectedRequest("blank patch option", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"options":["JPY"," "]}"""),
            RejectedRequest("duplicate patch options", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"options":["JPY"," jpy "]}"""),
            RejectedRequest("blank patch answer", HttpMethod.Patch, "/api/v1/questions/$ROUTE_ID", """{"correctAnswer":" "}"""),
            RejectedRequest("blank create text", HttpMethod.Post, "/api/v1/questions", """{"text":" ","options":["JPY","EUR"],"correctAnswer":"JPY"}"""),
            RejectedRequest("blank submitted answer", HttpMethod.Post, "/api/v1/questions/$ROUTE_ID/answers", """{"answer":" "}"""),
            RejectedRequest("oversized submitted answer", HttpMethod.Post, "/api/v1/questions/$ROUTE_ID/answers", buildJsonObject { put("answer", "x".repeat(201)) }.toString()),
            RejectedRequest("unknown JSON property", HttpMethod.Post, "/api/v1/questions", VALID_QUESTION_BODY.dropLast(1) + ",\"secret\":true}"),
            RejectedRequest("incomplete JSON body", HttpMethod.Post, "/api/v1/questions", "{"),
            RejectedRequest("missing required JSON field", HttpMethod.Post, "/api/v1/questions", """{"text":"Japan?","options":["JPY","EUR"]}"""),
        ) { row ->
            withRepository { repository ->
                val response = client.request(row.path) {
                    method = row.method
                    if (row.authenticated) bearerAuth(routeToken())
                    row.body?.let { jsonBody(it) }
                }

                response.status shouldBe row.status
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                verify { repository wasNot Called }
            }
        }
    }

    context("login input boundaries") {
        withTests(
            nameFn = { it.name },
            LoginCase("one-character username", username = "x", status = HttpStatusCode.Unauthorized),
            LoginCase("100-character username", username = "x".repeat(100), status = HttpStatusCode.Unauthorized),
            LoginCase("empty username", username = ""),
            LoginCase("blank username", username = " \t"),
            LoginCase("101-character username", username = "x".repeat(101)),
            LoginCase("one-character password", password = "x", status = HttpStatusCode.Unauthorized),
            LoginCase("1024-character password", password = "x".repeat(1024), status = HttpStatusCode.Unauthorized),
            LoginCase("blank password", password = " \t"),
            LoginCase("1025-character password", password = "x".repeat(1025)),
        ) { row ->
            withRepository { repository ->
                val response = client.post("/api/v1/auth/login") {
                    jsonBody(buildJsonObject { put("username", row.username); put("password", row.password) }.toString())
                }

                response.status shouldBe row.status
                response.errorCode() shouldBe if (row.status == HttpStatusCode.BadRequest) "validation_error" else "invalid_credentials"
                verify { repository wasNot Called }
            }
        }
    }

    test("repository validation failures return their reasons as safe client errors") {
        withRepository { repository ->
            val reasons = listOf("correctAnswer must match one of the options")
            coEvery { repository.update(ROUTE_ID, any()) } throws InvalidQuestionException(reasons)

            val response = client.patch("/api/v1/questions/$ROUTE_ID") {
                bearerAuth(routeToken())
                jsonBody("""{"correctAnswer":"USD"}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.errorCode() shouldBe "validation_error"
            response.json().jsonObject.getValue("details").jsonArray.map { it.jsonPrimitive.content } shouldBe reasons
            coVerify(exactly = 1) { repository.update(ROUTE_ID, any()) }
            confirmVerified(repository)
        }
    }

    test("unexpected repository exceptions cannot leak internal details") {
        withRepository { repository ->
            coEvery { repository.all() } throws IllegalStateException("database-password=private-value")

            val response = client.get("/api/v1/questions")

            response.status shouldBe HttpStatusCode.InternalServerError
            response.json() shouldBe buildJsonObject {
                put("code", "internal_error")
                put("message", "An unexpected error occurred")
                put("details", JsonArray(emptyList()))
            }
            coVerify(exactly = 1) { repository.all() }
            confirmVerified(repository)
        }
    }
})

private const val ROUTE_ID = "a1234567-89ab-cdef-0123-456789abcdef"
private const val SECOND_ID = "b1234567-89ab-cdef-0123-456789abcdef"
private const val ROUTE_USERNAME = "route-test-admin"
private const val ROUTE_PASSWORD = "route-test-password-123"
private const val VALID_QUESTION_BODY = """{"text":"Japan?","options":["JPY","EUR"],"correctAnswer":"JPY"}"""
private val ROUTE_QUESTION = Question(ROUTE_ID, "Which currency does Japan use?", listOf("JPY", "EUR"), "JPY")

private fun routeSettings() = ApiSettings(
    adminUsername = ROUTE_USERNAME,
    adminPassword = ROUTE_PASSWORD,
    jwtSecret = "route-test-signing-secret-at-least-thirty-two-bytes",
)

private fun routeToken() = requireNotNull(AuthService(routeSettings()).login(ROUTE_USERNAME, ROUTE_PASSWORD)).accessToken

private fun withRepository(block: suspend ApplicationTestBuilder.(QuestionRepository) -> Unit) {
    val repository = mockk<QuestionRepository>()
    testApplication {
        environment { config = MapApplicationConfig() }
        application { quizModule(routeSettings(), repository) }
        block(repository)
    }
}

private fun publicQuestionJson(question: Question) = buildJsonObject {
    put("id", question.id)
    put("text", question.text)
    put("options", JsonArray(question.options.map(::JsonPrimitive)))
}

private fun adminQuestionJson(question: Question) = JsonObject(publicQuestionJson(question) + ("correctAnswer" to JsonPrimitive(question.correctAnswer)))
private fun HttpRequestBuilder.jsonBody(body: String) { contentType(ContentType.Application.Json); setBody(body) }
private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())
private suspend fun HttpResponse.errorCode() = json().jsonObject.getValue("code").jsonPrimitive.content

private data class RejectedRequest(
    val name: String,
    val method: HttpMethod,
    val path: String,
    val body: String? = null,
    val authenticated: Boolean = true,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
)

private data class LoginCase(
    val name: String,
    val username: String = "invalid-user",
    val password: String = "invalid-password",
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
)
