package info.remzi.currencyquiz

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "Request validation failed", cause.reasons))
        }
        exception<InvalidQuestionException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "Request validation failed", cause.reasons))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Invalid request body or question ID"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Use Content-Type: application/json"))
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Use Content-Type: application/json"))
        }
        exception<QuestionNotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Question not found"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("not_found", "Resource not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError("method_not_allowed", "HTTP method is not supported for this resource"))
        }
        status(HttpStatusCode.Forbidden) { call, status ->
            call.respond(status, ApiError("forbidden", "Request is not allowed"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ApiError("rate_limit_exceeded", "Too many requests; try again later"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "An unexpected error occurred"))
        }
    }
}
