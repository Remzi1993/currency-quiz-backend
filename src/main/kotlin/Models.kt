package info.remzi.currencyquiz

import kotlinx.serialization.Serializable

@Serializable
data class Question(val id: String, val text: String, val options: List<String>, val correctAnswer: String) {
    fun publicView() = PublicQuestion(id, text, options)
}

@Serializable
data class PublicQuestion(val id: String, val text: String, val options: List<String>)

@Serializable
data class CreateQuestionRequest(val text: String, val options: List<String>, val correctAnswer: String)

@Serializable
data class UpdateQuestionRequest(
    val text: String? = null,
    val options: List<String>? = null,
    val correctAnswer: String? = null,
)

@Serializable
data class AnswerRequest(val answer: String)

@Serializable
data class AnswerResponse(val correct: Boolean)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val accessToken: String, val tokenType: String = "Bearer", val expiresIn: Long)

@Serializable
data class ApiError(val code: String, val message: String, val details: List<String> = emptyList())

class InvalidQuestionException(val reasons: List<String>) : RuntimeException("Invalid question")
class QuestionNotFoundException : RuntimeException("Question not found")
