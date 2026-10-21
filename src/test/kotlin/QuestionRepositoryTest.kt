package info.remzi.currencyquiz

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class QuestionRepositoryTest {
    @Test
    fun `concurrent mutations do not lose questions or produce an unreadable snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-concurrency-test-")
        try {
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, "[]")
            val repository = FileQuestionRepository(storage)
            val created = (1..20).map { number ->
                async {
                    repository.create(CreateQuestionRequest("Currency question $number?", listOf("JPY", "EUR"), "JPY"))
                }
            }.awaitAll()
            assertEquals(20, created.map { it.id }.toSet().size)
            assertEquals(created.toSet(), repository.all().toSet())
            assertEquals(created.toSet(), FileQuestionRepository(storage).all().toSet())

            created.map { question ->
                async { repository.update(question.id, UpdateQuestionRequest(text = "Updated ${question.id}?")) }
            }.awaitAll()
            assertTrue(FileQuestionRepository(storage).all().all { it.text.startsWith("Updated ") })

            created.map { question -> async { repository.delete(question.id) } }.awaitAll()
            assertTrue(repository.all().isEmpty())
            assertTrue(FileQuestionRepository(storage).all().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted content is rejected without overwriting the original file`() {
        val directory = Files.createTempDirectory("currencyquiz-corrupt-storage-test-")
        try {
            val storage = directory.resolve("questions.json")
            val invalidContents = listOf(
                "{malformed-json",
                """[{"id":"not-a-uuid","text":"Currency?","options":["JPY","EUR"],"correctAnswer":"JPY"}]""",
                """[{"id":"12345678-1234-1234-1234-123456789012","text":"Currency?","options":["JPY","EUR"],"correctAnswer":"USD"}]""",
            )
            for (contents in invalidContents) {
                Files.writeString(storage, contents)
                assertFailsWith<IllegalArgumentException> { FileQuestionRepository(storage) }
                assertEquals(contents, Files.readString(storage))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing storage creates parent directories and persists the complete bundled quiz`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-missing-storage-test-")
        try {
            val storage = directory.resolve("nested").resolve("content").resolve("questions.json")
            val expected = seedQuestions()
            assertEquals(40, expected.size)
            assertFalse(Files.exists(storage))

            val repository = FileQuestionRepository(storage)

            assertTrue(Files.isRegularFile(storage))
            assertEquals(expected, repository.all())
            assertEquals(expected, storedQuestions(storage))
            assertEquals(expected, FileQuestionRepository(storage).all())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `zero-byte and whitespace-only storage are initialized from the bundled quiz`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-blank-storage-test-")
        try {
            val expected = seedQuestions()
            for ((index, blank) in listOf("", " \t\r\n  \n").withIndex()) {
                val storage = directory.resolve("questions-$index.json")
                Files.writeString(storage, blank)

                val repository = FileQuestionRepository(storage)

                assertEquals(expected, repository.all())
                assertEquals(expected, storedQuestions(storage))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `existing custom quiz content is retained without rewriting its file`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-existing-storage-test-")
        try {
            val storage = directory.resolve("questions.json")
            val custom = listOf(Question(UUID.randomUUID().toString(), "My own question?", listOf("JPY", "EUR"), "EUR"))
            val contents = "\n  ${Json.encodeToString(custom)}\n"
            Files.writeString(storage, contents)

            assertEquals(custom, FileQuestionRepository(storage).all())
            assertEquals(contents, Files.readString(storage))
            assertEquals(custom, FileQuestionRepository(storage).all())
            assertEquals(contents, Files.readString(storage))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an intentional empty array and deleting all seeded questions remain empty after restart`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-deleted-seed-test-")
        try {
            val explicitEmpty = directory.resolve("intentionally-empty.json")
            Files.writeString(explicitEmpty, "[]")
            assertTrue(FileQuestionRepository(explicitEmpty).all().isEmpty())
            assertTrue(FileQuestionRepository(explicitEmpty).all().isEmpty())
            assertEquals("[]", Files.readString(explicitEmpty))

            val storage = directory.resolve("seeded-then-deleted.json")
            val repository = FileQuestionRepository(storage)
            val questions = repository.all()
            assertEquals(40, questions.size)
            questions.forEach { assertTrue(repository.delete(it.id)) }

            assertTrue(repository.all().isEmpty())
            assertTrue(storedQuestions(storage).isEmpty())
            assertTrue(FileQuestionRepository(storage).all().isEmpty())
            assertTrue(FileQuestionRepository(storage).all().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun seedQuestions(): List<Question> {
        val resource = assertNotNull(javaClass.getResourceAsStream("/questions-seed.json"))
        return resource.bufferedReader().use { Json.decodeFromString(it.readText()) }
    }

    private fun storedQuestions(storage: Path): List<Question> = Json.decodeFromString(Files.readString(storage))
}
