package info.remzi.currencyquiz

import com.auth0.jwt.JWT
import info.remzi.currencyquiz.contract.LoginRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Application.configureAuthRouting(auth: AuthService, tokens: JwtTokens, browser: BrowserTokens) {
    routing {
        route("/api/v1/auth") {
            rateLimit(RateLimitName("login")) {
                post("/login") {
                    val request = call.receive<LoginRequest>()
                    call.response.headers.append("Cache-Control", "no-store")
                    val token = auth.login(request.username, request.password)
                    if (token == null) call.invalidCredentials() else call.respond(token)
                }
                post("/session") {
                    if (!call.requireBrowserOrigin(browser)) return@post
                    val request = call.receive<LoginRequest>()
                    if (!auth.authenticates(request.username, request.password)) {
                        call.invalidCredentials()
                        return@post
                    }
                    call.respondBrowserTokens(browser, tokens.startLogin())
                }
            }
            get("/session") {
                // Same-origin GET fetches may omit Origin; explicit untrusted origins are rejected.
                if (!call.requireBrowserOrigin(browser, allowAbsent = true)) return@get
                val metadata = browser.metadata(call.accessToken(tokens), call.refreshToken(tokens))
                if (metadata == null) call.invalidBrowserToken() else call.respond(metadata)
            }
            delete("/session") {
                if (!call.requireBrowserOrigin(browser)) return@delete
                val credential = call.refreshToken(tokens) ?: call.accessToken(tokens)
                if (credential == null) {
                    call.expiredBrowserToken(browser)
                } else if (call.requireBrowserCsrf(browser, credential)) {
                    call.clearBrowserCookies(browser)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            rateLimit(RateLimitName("refresh")) {
                post("/session/refresh") {
                    if (!call.requireBrowserOrigin(browser)) return@post
                    val refresh = call.refreshToken(tokens)
                    if (refresh == null) {
                        call.expiredBrowserToken(browser)
                    } else if (call.requireBrowserCsrf(browser, refresh)) {
                        val issued = tokens.renew(refresh)
                        if (issued == null) {
                            call.expiredBrowserToken(browser)
                            return@post
                        }
                        call.respondBrowserTokens(browser, issued)
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.accessToken(tokens: JwtTokens) = tokens.verifyAccess(request.cookies[ADMIN_COOKIE_NAME])
private fun ApplicationCall.refreshToken(tokens: JwtTokens) = tokens.verifyRefresh(request.cookies[ADMIN_REFRESH_COOKIE_NAME])

private suspend fun ApplicationCall.invalidCredentials() =
    respond(HttpStatusCode.Unauthorized, apiError("invalid_credentials", "Invalid username or password"))

private suspend fun ApplicationCall.invalidBrowserToken() =
    respond(HttpStatusCode.Unauthorized, apiError("unauthorized", "A valid admin JWT cookie is required"))

private suspend fun ApplicationCall.expiredBrowserToken(browser: BrowserTokens) {
    clearBrowserCookies(browser)
    invalidBrowserToken()
}

private suspend fun ApplicationCall.respondBrowserTokens(browser: BrowserTokens, issued: IssuedTokens) {
    val metadata = browser.metadata(JWT.decode(issued.accessToken), JWT.decode(issued.refreshToken))
    if (metadata == null) {
        expiredBrowserToken(browser)
        return
    }
    response.cookies.append(browser.cookie(issued.accessToken))
    response.cookies.append(browser.cookie(issued.refreshToken, refresh = true))
    respond(metadata)
}

private fun ApplicationCall.clearBrowserCookies(browser: BrowserTokens) {
    response.cookies.append(browser.cookie("", clearing = true))
    response.cookies.append(browser.cookie("", refresh = true, clearing = true))
}
