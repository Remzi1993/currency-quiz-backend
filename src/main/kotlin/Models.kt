package info.remzi.currencyquiz

import kotlinx.serialization.Serializable
import info.remzi.currencyquiz.contract.AdminQuestion
import info.remzi.currencyquiz.contract.ApiError
import info.remzi.currencyquiz.contract.PublicQuestion

@Serializable
data class Question(val id: String, val text: String, val options: List<String>, val correctAnswer: String) {
    fun publicView() = PublicQuestion(id, text, options.toTypedArray())
    fun adminView() = AdminQuestion(id, text, options.toTypedArray(), correctAnswer)
}

class InvalidQuestionException(val reasons: List<String>) : RuntimeException("Invalid question")
class QuestionNotFoundException : RuntimeException("Question not found")

internal fun apiError(code: String, message: String, details: List<String> = emptyList()) =
    ApiError(code, message, details.toTypedArray())
