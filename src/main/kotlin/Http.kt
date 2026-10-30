package info.remzi.currencyquiz

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import java.net.URI

fun Application.configureHttp(settings: ApiSettings) {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        exposeHeader(HttpHeaders.Location)
        exposeHeader(HttpHeaders.RetryAfter)
        allowNonSimpleContentTypes = true
        allowCredentials = false
        if ("*" in settings.allowedOrigins) {
            anyHost()
        } else {
            settings.allowedOrigins.forEach { origin ->
                val uri = URI(origin)
                allowHost(uri.rawAuthority, schemes = listOf(uri.scheme))
            }
        }
    }
    routing {
        swaggerUI(path = "openapi", swaggerFile = "documentation.yaml")
    }
}
