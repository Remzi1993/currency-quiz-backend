package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.*

import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*
import java.util.Locale

internal fun String.normalizedAnswer() = trim().lowercase(Locale.ROOT)

internal fun questionValidationErrors(text: String, options: List<String>, correctAnswer: String): List<String> =
    buildList {
        if (text.trim().length !in 1..500) add("text must contain 1 to 500 characters")
        if (options.size !in 2..10) add("options must contain 2 to 10 choices")
        if (options.any { it.trim().length !in 1..200 }) add("Each option must contain 1 to 200 characters")
        if (options.map { it.normalizedAnswer() }.distinct().size != options.size) add("options must be unique")
        if (correctAnswer.trim().length !in 1..200) add("correctAnswer must contain 1 to 200 characters")
        if (options.none { it.normalizedAnswer() == correctAnswer.normalizedAnswer() }) {
            add("correctAnswer must match one of the options")
        }
    }

private fun List<String>.validationResult(): ValidationResult =
    if (isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(this)

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<CreateQuestionRequest> { question ->
            questionValidationErrors(question.text, question.options.toList(), question.correctAnswer).validationResult()
        }
        validate<UpdateQuestionRequest> { patch ->
            buildList {
                if (patch.text == null && patch.options == null && patch.correctAnswer == null) {
                    add("Supply at least one of text, options or correctAnswer")
                }
                if (patch.text != null && patch.text.trim().length !in 1..500) {
                    add("text must contain 1 to 500 characters")
                }
                if (patch.options != null) {
                    if (patch.options.size !in 2..10) add("options must contain 2 to 10 choices")
                    if (patch.options.any { it.trim().length !in 1..200 }) {
                        add("Each option must contain 1 to 200 characters")
                    }
                    if (patch.options.map { it.normalizedAnswer() }.distinct().size != patch.options.size) {
                        add("options must be unique")
                    }
                }
                if (patch.correctAnswer != null && patch.correctAnswer.trim().length !in 1..200) {
                    add("correctAnswer must contain 1 to 200 characters")
                }
            }.validationResult()
        }
        validate<AnswerRequest> { answer ->
            if (answer.answer.trim().length in 1..200) ValidationResult.Valid
            else ValidationResult.Invalid("answer must contain 1 to 200 characters")
        }
        validate<LoginRequest> { credentials ->
            buildList {
                if (credentials.username.isBlank() || credentials.username.length > 100) {
                    add("username must contain 1 to 100 characters")
                }
                if (credentials.password.isBlank() || credentials.password.length > 1024) {
                    add("password must contain 1 to 1024 characters")
                }
            }.validationResult()
        }
    }
}
