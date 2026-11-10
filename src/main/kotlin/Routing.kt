package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.*

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.configureRouting(repository: QuestionRepository, browser: BrowserTokens) {
    routing {
        get("/") { call.respond(mapOf("status" to "ok")) }
        route("/api/v1") {
            get("/questions") {
                call.respond(repository.all().map { it.publicView() })
            }
            get("/questions/{id}") {
                val question = repository.find(call.questionId()) ?: throw QuestionNotFoundException()
                call.respond(question.publicView())
            }
            rateLimit(RateLimitName("answers")) {
                post("/questions/{id}/answers") {
                    val id = call.questionId()
                    val request = call.receive<AnswerRequest>()
                    val question = repository.find(id) ?: throw QuestionNotFoundException()
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.respond(AnswerResponse(request.answer.normalizedAnswer() == question.correctAnswer.normalizedAnswer()))
                }
            }
            authenticate("admin-jwt") {
                post("/questions") {
                    if (!call.authorizeCookieMutation(browser)) return@post
                    val request = call.receive<CreateQuestionRequest>()
                    val question = repository.create(request)
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/questions/${question.id}")
                    call.respond(HttpStatusCode.Created, question.adminView())
                }
                patch("/questions/{id}") {
                    if (!call.authorizeCookieMutation(browser)) return@patch
                    val id = call.questionId()
                    val request = call.receive<UpdateQuestionRequest>()
                    val question = repository.update(id, request) ?: throw QuestionNotFoundException()
                    call.respond(question.adminView())
                }
                delete("/questions/{id}") {
                    if (!call.authorizeCookieMutation(browser)) return@delete
                    if (!repository.delete(call.questionId())) throw QuestionNotFoundException()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.authorizeCookieMutation(browser: BrowserTokens): Boolean {
    if (request.headers[HttpHeaders.Authorization] != null) return true
    val token = requireNotNull(principal<io.ktor.server.auth.jwt.JWTPrincipal>()).payload
    return requireBrowserCsrf(browser, token)
}

private fun ApplicationCall.questionId(): String {
    val value = parameters["id"] ?: throw BadRequestException("Missing question ID")
    if (!isQuestionId(value)) throw BadRequestException("Question ID must be a UUID")
    return UUID.fromString(value).toString()
}
