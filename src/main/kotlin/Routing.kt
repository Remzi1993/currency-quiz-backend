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

fun Application.configureRouting(repository: QuestionRepository, authService: AuthService, browserSessions: BrowserSessions) {
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
                post("/auth/session") {
                    if (!call.requireBrowserOrigin(browserSessions)) return@post
                    val request = call.receive<LoginRequest>()
                    if (!authService.authenticates(request.username, request.password)) {
                        call.respond(HttpStatusCode.Unauthorized, apiError("invalid_credentials", "Invalid username or password"))
                    } else {
                        val issued = browserSessions.create(call.request.cookies[ADMIN_COOKIE_NAME], call.request.cookies[ADMIN_REFRESH_COOKIE_NAME])
                        call.setBrowserCookies(browserSessions, issued)
                        call.respond(browserSessions.response(issued.session))
                    }
                }
            }
            get("/auth/session") {
                // Same-origin GET fetches may omit Origin; an explicit untrusted Origin is still rejected.
                if (!call.requireBrowserOrigin(browserSessions, allowAbsent = true)) return@get
                val session = call.browserSession(browserSessions)
                if (session == null) {
                    call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "A valid admin browser session is required"))
                } else call.respond(browserSessions.response(session))
            }
            delete("/auth/session") {
                if (!call.requireBrowserOrigin(browserSessions)) return@delete
                val cookie = call.request.cookies[ADMIN_COOKIE_NAME]
                val session = call.browserSession(browserSessions)
                if (session == null) {
                    call.clearBrowserCookies(browserSessions)
                    call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "A valid admin browser session is required"))
                } else if (call.requireBrowserCsrf(browserSessions, session)) {
                    browserSessions.revoke(cookie)
                    browserSessions.revoke(call.request.cookies[ADMIN_REFRESH_COOKIE_NAME])
                    call.clearBrowserCookies(browserSessions)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            rateLimit(RateLimitName("session")) {
                post("/auth/session/refresh") {
                    if (!call.requireBrowserOrigin(browserSessions)) return@post
                    val refresh = call.request.cookies[ADMIN_REFRESH_COOKIE_NAME]
                    val session = browserSessions.findRefresh(refresh, includeUsed = true)
                    if (session == null) {
                        call.clearBrowserCookies(browserSessions)
                        call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "The browser session has ended"))
                    } else if (call.requireBrowserCsrf(browserSessions, session)) {
                        val issued = browserSessions.rotate(requireNotNull(refresh))
                        if (issued == null) {
                            call.clearBrowserCookies(browserSessions)
                            call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "The browser session has ended"))
                        } else {
                            call.setBrowserCookies(browserSessions, issued)
                            call.respond(browserSessions.response(issued.session))
                        }
                    }
                }
                post("/auth/session/activity") {
                    if (!call.requireBrowserOrigin(browserSessions)) return@post
                    val session = browserSessions.find(call.request.cookies[ADMIN_COOKIE_NAME])
                    if (session == null) {
                        call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "A valid access cookie is required"))
                    } else if (call.requireBrowserCsrf(browserSessions, session)) {
                        if (!browserSessions.recordActivity(session)) {
                            call.respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "The browser session has ended"))
                        } else call.respond(browserSessions.response(session))
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
            authenticate("admin-jwt", "admin-cookie") {
                post("/questions") {
                    if (!call.authorizeCookieMutation(browserSessions)) return@post
                    val request = call.receive<CreateQuestionRequest>()
                    val question = repository.create(request)
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/questions/${question.id}")
                    call.respond(HttpStatusCode.Created, question.adminView())
                }
                patch("/questions/{id}") {
                    if (!call.authorizeCookieMutation(browserSessions)) return@patch
                    val id = call.questionId()
                    val request = call.receive<UpdateQuestionRequest>()
                    val question = repository.update(id, request) ?: throw QuestionNotFoundException()
                    call.respond(question.adminView())
                }
                delete("/questions/{id}") {
                    if (!call.authorizeCookieMutation(browserSessions)) return@delete
                    if (!repository.delete(call.questionId())) throw QuestionNotFoundException()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.authorizeCookieMutation(sessions: BrowserSessions): Boolean {
    val session = principal<BrowserSessionPrincipal>()?.session ?: return true
    if (!requireBrowserCsrf(sessions, session)) return false
    if (sessions.recordActivity(session)) return true
    respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "The browser session has ended"))
    return false
}

private fun ApplicationCall.browserSession(sessions: BrowserSessions): BrowserSession? =
    if (request.cookies[ADMIN_REFRESH_COOKIE_NAME] != null) sessions.findRefresh(request.cookies[ADMIN_REFRESH_COOKIE_NAME])
    else sessions.find(request.cookies[ADMIN_COOKIE_NAME])

private fun ApplicationCall.setBrowserCookies(sessions: BrowserSessions, issued: IssuedBrowserSession) {
    response.cookies.append(sessions.cookie(issued.cookieValue, session = issued.session))
    response.cookies.append(sessions.cookie(issued.refreshValue, refresh = true, session = issued.session))
}

private fun ApplicationCall.clearBrowserCookies(sessions: BrowserSessions) {
    response.cookies.append(sessions.cookie("", clearing = true))
    response.cookies.append(sessions.cookie("", clearing = true, refresh = true))
}

private fun ApplicationCall.questionId(): String {
    val value = parameters["id"] ?: throw BadRequestException("Missing question ID")
    if (!isQuestionId(value)) throw BadRequestException("Question ID must be a UUID")
    return UUID.fromString(value).toString()
}
