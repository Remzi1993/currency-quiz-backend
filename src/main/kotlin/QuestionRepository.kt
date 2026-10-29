package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.CreateQuestionRequest
import info.remzi.currencyquiz.contract.UpdateQuestionRequest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

interface QuestionRepository {
    suspend fun all(): List<Question>
    suspend fun find(id: String): Question?
    suspend fun create(request: CreateQuestionRequest): Question
    suspend fun update(id: String, request: UpdateQuestionRequest): Question?
    suspend fun delete(id: String): Boolean
}

/** Single-process storage. Mutations reach disk before the in-memory snapshot is published. */
class FileQuestionRepository(file: Path) : QuestionRepository {
    private val file = file.toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }
    private val mutex = Mutex()
    private var questions: Map<String, Question>

    init {
        Files.createDirectories(this.file.parent)
        val existingContent = if (Files.exists(this.file)) Files.readString(this.file) else null
        val needsSeed = existingContent.isNullOrBlank()
        val content = if (needsSeed) {
            FileQuestionRepository::class.java.getResourceAsStream("/questions-seed.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Missing bundled questions-seed.json resource")
        } else {
            requireNotNull(existingContent)
        }
        val stored = json.decodeFromString<List<Question>>(content)
        require(stored.map { it.id }.distinct().size == stored.size) { "Duplicate question IDs in storage" }
        stored.forEach {
            require(isQuestionId(it.id)) { "Invalid question ID in storage" }
            require(questionValidationErrors(it.text, it.options, it.correctAnswer).isEmpty()) { "Invalid question in storage" }
        }
        if (needsSeed) persistContent(content)
        questions = stored.associateBy { it.id }
    }

    override suspend fun all(): List<Question> = mutex.withLock { questions.values.toList() }

    override suspend fun find(id: String): Question? = mutex.withLock { questions[id] }

    override suspend fun create(request: CreateQuestionRequest): Question = withContext(Dispatchers.IO) {
        mutex.withLock {
            val question = validatedQuestion(UUID.randomUUID().toString(), request.text, request.options.toList(), request.correctAnswer)
            val updated = questions + (question.id to question)
            persist(updated.values.toList())
            questions = updated
            question
        }
    }

    override suspend fun update(id: String, request: UpdateQuestionRequest): Question? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = questions[id] ?: return@withLock null
            val question = validatedQuestion(
                id, request.text ?: existing.text, request.options?.toList() ?: existing.options,
                request.correctAnswer ?: existing.correctAnswer,
            )
            val updated = questions + (id to question)
            persist(updated.values.toList())
            questions = updated
            question
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (id !in questions) return@withLock false
            val updated = questions - id
            persist(updated.values.toList())
            questions = updated
            true
        }
    }

    private fun validatedQuestion(id: String, text: String, options: List<String>, correctAnswer: String): Question {
        val errors = questionValidationErrors(text, options, correctAnswer)
        if (errors.isNotEmpty()) throw InvalidQuestionException(errors)
        val trimmedOptions = options.map { it.trim() }
        return Question(id, text.trim(), trimmedOptions, trimmedOptions.first {
            it.normalizedAnswer() == correctAnswer.normalizedAnswer()
        })
    }

    private fun persist(snapshot: List<Question>) {
        persistContent(json.encodeToString(snapshot))
    }

    private fun persistContent(content: String) {
        val temporaryFile = Files.createTempFile(file.parent, ".questions-", ".tmp")
        try {
            Files.writeString(temporaryFile, content)
            try {
                Files.move(temporaryFile, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }
}

private val questionIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal fun isQuestionId(value: String): Boolean = questionIdPattern.matches(value)
