package info.remzi.currencyquiz

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

/** Public CORS and credentialed admin CORS have different origin policies. */
fun Application.configureHttp(settings: ApiSettings) {
    val methods = setOf("GET", "HEAD", "OPTIONS", "POST", "PATCH", "DELETE")
    val publicHeaders = setOf("accept", "accept-language", "content-language", "content-type", "authorization")
    install(createApplicationPlugin("QuizCors") {
        onCall { call ->
            val sessionPath = call.request.path().startsWith("/api/v1/auth/session")
            if (sessionPath || call.request.headers[HttpHeaders.Authorization] != null
                || call.request.cookies[ADMIN_COOKIE_NAME] != null) call.response.header(HttpHeaders.CacheControl, "no-store")
            // Credentialed and public responses differ by Origin, including requests without Origin.
            call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
            val origins = call.request.headers.getAll(HttpHeaders.Origin) ?: return@onCall
            if (origins.size != 1) {
                call.respond(HttpStatusCode.Forbidden)
                return@onCall
            }
            val origin = origins.single()
            val adminOrigin = origin in settings.adminAllowedOrigins
            val publicOrigin = "*" in settings.allowedOrigins || origin in settings.allowedOrigins
            if ((!adminOrigin && !publicOrigin) || (sessionPath && !adminOrigin)) {
                call.respond(HttpStatusCode.Forbidden)
                return@onCall
            }
            call.response.header(HttpHeaders.AccessControlAllowOrigin, if (adminOrigin || "*" !in settings.allowedOrigins) origin else "*")
            if (adminOrigin) call.response.header(HttpHeaders.AccessControlAllowCredentials, "true")
            call.response.header(HttpHeaders.AccessControlExposeHeaders, "Location, Retry-After")

            if (call.request.httpMethod == HttpMethod.Options && call.request.headers[HttpHeaders.AccessControlRequestMethod] != null) {
                val requestedMethods = call.request.headers.getAll(HttpHeaders.AccessControlRequestMethod)
                val requestedHeaders = call.request.headers.getAll(HttpHeaders.AccessControlRequestHeaders)
                    .orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }
                val allowedHeaders = if (adminOrigin) publicHeaders + CSRF_HEADER.lowercase() else publicHeaders
                if (requestedMethods?.size != 1 || requestedMethods.single() !in methods
                    || requestedHeaders.any { it !in allowedHeaders }) {
                    call.respond(HttpStatusCode.Forbidden)
                } else {
                    call.response.header(HttpHeaders.AccessControlAllowMethods, methods.joinToString(", "))
                    if (requestedHeaders.isNotEmpty()) call.response.header(HttpHeaders.AccessControlAllowHeaders, requestedHeaders.joinToString(", "))
                    call.response.header(HttpHeaders.Vary, "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    })
    routing {
        swaggerUI(path = "openapi", swaggerFile = "documentation.yaml")
    }
}
