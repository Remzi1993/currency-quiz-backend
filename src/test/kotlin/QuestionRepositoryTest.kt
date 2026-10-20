package info.remzi.currencyquiz

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class QuestionRepositoryTest {
    @Test
    fun `concurrent mutations do not lose questions or produce an unreadable snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("currencyquiz-concurrency-test-")
        try {
            val storage = directory.resolve("questions.json")
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
}
