package info.remzi.currencyquiz

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import java.nio.file.Path

fun Application.module() {
    val settings = ApiSettings.from(environment.config)
    val repository = FileQuestionRepository(Path.of(settings.questionsFile))
    quizModule(settings, repository)
}

fun Application.quizModule(settings: ApiSettings, repository: QuestionRepository) {
    install(CallLogging) {
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    configureSerialization()
    configureStatusPages()
    configureRequestValidation()
    configureSecurity(settings)
    configureRateLimiting()
    configureHttp(settings)
    configureRouting(repository, AuthService(settings))
}
