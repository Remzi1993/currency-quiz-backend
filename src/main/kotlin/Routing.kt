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

fun Application.configureRouting(repository: QuestionRepository, authService: AuthService) {
    routing {
        get("/") { call.respond(mapOf("status" to "ok")) }
        route("/api/v1") {
            rateLimit(RateLimitName("login")) {
                post("/auth/login") {
                    val request = call.receive<LoginRequest>()
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    val token = authService.login(request.username, request.password)
                    if (token == null) {
                        call.respond(HttpStatusCode.Unauthorized, apiError("invalid_credentials", "Invalid username or password"))
                    } else {
                        call.respond(token)
                    }
                }
            }
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
                    val request = call.receive<CreateQuestionRequest>()
                    val question = repository.create(request)
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/questions/${question.id}")
                    call.respond(HttpStatusCode.Created, question.adminView())
                }
                patch("/questions/{id}") {
                    val id = call.questionId()
                    val request = call.receive<UpdateQuestionRequest>()
                    val question = repository.update(id, request) ?: throw QuestionNotFoundException()
                    call.respond(question.adminView())
                }
                delete("/questions/{id}") {
                    if (!repository.delete(call.questionId())) throw QuestionNotFoundException()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun ApplicationCall.questionId(): String {
    val value = parameters["id"] ?: throw BadRequestException("Missing question ID")
    if (!isQuestionId(value)) throw BadRequestException("Question ID must be a UUID")
    return UUID.fromString(value).toString()
}
