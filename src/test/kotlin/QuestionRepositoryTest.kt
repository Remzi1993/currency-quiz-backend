package info.remzi.currencyquiz

import info.remzi.currencyquiz.contract.CreateQuestionRequest
import info.remzi.currencyquiz.contract.UpdateQuestionRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class QuestionRepositoryTest : FunSpec() {
    init {
        test("create normalizes text options and canonical answer and survives reload") {
            withStorage { storage ->
                val repository = FileQuestionRepository(storage)

                val created = repository.create(CreateQuestionRequest("  Currency of Japan?  ", arrayOf("  JPY  ", " EUR "), " jPy "))

                UUID.fromString(created.id).toString() shouldBe created.id
                created.text shouldBe "Currency of Japan?"
                created.options shouldBe listOf("JPY", "EUR")
                created.correctAnswer shouldBe "JPY"
                repository.find(created.id) shouldBe created
                repository.all() shouldBe listOf(created)
                FileQuestionRepository(storage).find(created.id) shouldBe created
                storedQuestions(storage) shouldBe listOf(created)
            }
        }

        test("partial updates preserve omitted fields and use the resulting options to validate answers") {
            withStorage { storage ->
                val repository = FileQuestionRepository(storage)
                val initial = repository.create(questionRequest())
                val renamed = repository.update(initial.id, UpdateQuestionRequest(text = "  Updated question?  ")).shouldNotBeNull()
                renamed shouldBe initial.copy(text = "Updated question?")
                val changedAnswer = repository.update(initial.id, UpdateQuestionRequest(correctAnswer = " eUr ")).shouldNotBeNull()
                changedAnswer shouldBe renamed.copy(correctAnswer = "EUR")
                val changedOptions = repository.update(initial.id, UpdateQuestionRequest(options = arrayOf(" EUR ", "USD"))).shouldNotBeNull()
                changedOptions shouldBe changedAnswer.copy(options = listOf("EUR", "USD"))
                val replaced = repository.update(
                    initial.id,
                    UpdateQuestionRequest(text = "  Currency of the USA? ", options = arrayOf(" USD ", "GBP"), correctAnswer = "usd"),
                ).shouldNotBeNull()

                replaced shouldBe initial.copy(text = "Currency of the USA?", options = listOf("USD", "GBP"), correctAnswer = "USD")
                FileQuestionRepository(storage).all() shouldBe listOf(replaced)
            }
        }

        test("find update and delete of unknown IDs leave the persisted snapshot unchanged") {
            withStorage { storage ->
                val repository = FileQuestionRepository(storage)
                val existing = repository.create(questionRequest())
                val originalContent = Files.readString(storage)
                val missingId = UUID.randomUUID().toString()

                repository.find(missingId).shouldBeNull()
                repository.update(missingId, UpdateQuestionRequest(text = "Missing?")).shouldBeNull()
                repository.delete(missingId) shouldBe false

                repository.all() shouldBe listOf(existing)
                Files.readString(storage) shouldBe originalContent
            }
        }

        test("deleting a question preserves other questions and persists deletion") {
            withStorage { storage ->
                val repository = FileQuestionRepository(storage)
                val first = repository.create(questionRequest())
                val second = repository.create(questionRequest("Another currency?"))

                repository.delete(first.id) shouldBe true
                repository.delete(first.id) shouldBe false

                repository.find(first.id).shouldBeNull()
                repository.all() shouldBe listOf(second)
                FileQuestionRepository(storage).all() shouldBe listOf(second)
            }
        }

        context("invalid creates preserve both memory and disk") {
            withTests(
                nameFn = { it.first },
                "blank text" to CreateQuestionRequest(" ", arrayOf("JPY", "EUR"), "JPY"),
                "too few options" to CreateQuestionRequest("Currency?", arrayOf("JPY"), "JPY"),
                "duplicate normalized options" to CreateQuestionRequest("Currency?", arrayOf("JPY", " jPy "), "JPY"),
                "unlisted answer" to CreateQuestionRequest("Currency?", arrayOf("JPY", "EUR"), "USD"),
            ) { (_, request) ->
                withStorage { storage ->
                    val repository = FileQuestionRepository(storage)
                    val existing = repository.create(questionRequest())
                    val originalContent = Files.readString(storage)

                    shouldThrow<InvalidQuestionException> { repository.create(request) }.reasons.isEmpty() shouldBe false

                    repository.all() shouldBe listOf(existing)
                    Files.readString(storage) shouldBe originalContent
                    FileQuestionRepository(storage).all() shouldBe listOf(existing)
                }
            }
        }

        context("invalid partial updates preserve both memory and disk") {
            withTests(
                nameFn = { it.first },
                "blank text" to UpdateQuestionRequest(text = " "),
                "removing the correct option" to UpdateQuestionRequest(options = arrayOf("USD", "GBP")),
                "unlisted answer" to UpdateQuestionRequest(correctAnswer = "USD"),
                "duplicate normalized options" to UpdateQuestionRequest(options = arrayOf("JPY", " jpy ")),
            ) { (_, patch) ->
                withStorage { storage ->
                    val repository = FileQuestionRepository(storage)
                    val existing = repository.create(questionRequest())
                    val originalContent = Files.readString(storage)

                    shouldThrow<InvalidQuestionException> { repository.update(existing.id, patch) }

                    repository.find(existing.id) shouldBe existing
                    Files.readString(storage) shouldBe originalContent
                    FileQuestionRepository(storage).all() shouldBe listOf(existing)
                }
            }
        }

        context("failed persistence never publishes an uncommitted mutation") {
            withTests("create", "update", "delete") { operation ->
                withStorage { storage ->
                    val repository = FileQuestionRepository(storage)
                    val existing = repository.create(questionRequest())
                    val originalContent = Files.readString(storage)
                    // A nonempty directory at the destination causes moves to fail on Windows and Unix,
                    // including when tests run with privileges that bypass ordinary file permissions.
                    Files.delete(storage)
                    Files.createDirectory(storage)
                    val blocker = Files.writeString(storage.resolve("keep-directory-nonempty"), "block")
                    try {
                        shouldThrow<IOException> {
                            when (operation) {
                                "create" -> repository.create(questionRequest("Uncommitted?"))
                                "update" -> repository.update(existing.id, UpdateQuestionRequest(text = "Uncommitted?"))
                                "delete" -> repository.delete(existing.id)
                            }
                        }

                        repository.all() shouldBe listOf(existing)
                        repository.find(existing.id) shouldBe existing
                        Files.list(storage.parent).use { files ->
                            files.filter { it.fileName.toString().startsWith(".questions-") }.count() shouldBe 0L
                        }
                    } finally {
                        Files.delete(blocker)
                        Files.delete(storage)
                        Files.writeString(storage, originalContent)
                    }
                    FileQuestionRepository(storage).all() shouldBe listOf(existing)
                }
            }
        }

        test("concurrent mutations do not lose questions or produce an unreadable snapshot") {
            withStorage { storage ->
                val repository = FileQuestionRepository(storage)
                val created = coroutineScope {
                    (1..20).map { number ->
                        async { repository.create(questionRequest("Currency question $number?")) }
                    }.awaitAll()
                }
                created.map { it.id }.toSet().size shouldBe 20
                repository.all().toSet() shouldBe created.toSet()
                FileQuestionRepository(storage).all().toSet() shouldBe created.toSet()

                coroutineScope {
                    created.map { question ->
                        async { repository.update(question.id, UpdateQuestionRequest(text = "Updated ${question.id}?")) }
                    }.awaitAll()
                }
                FileQuestionRepository(storage).all().map { it.text }.toSet() shouldBe
                    created.map { "Updated ${it.id}?" }.toSet()

                coroutineScope {
                    created.map { question -> async { repository.delete(question.id) } }.awaitAll()
                }.all { it } shouldBe true
                repository.all() shouldBe emptyList()
                FileQuestionRepository(storage).all() shouldBe emptyList()
            }
        }

        context("invalid persisted content is rejected without overwriting the original file") {
            val valid = Question("12345678-1234-1234-1234-123456789012", "Currency?", listOf("JPY", "EUR"), "JPY")
            withTests(
                nameFn = { it.first },
                "malformed JSON" to "{malformed-json",
                "invalid UUID" to Json.encodeToString(listOf(valid.copy(id = "not-a-uuid"))),
                "unlisted answer" to Json.encodeToString(listOf(valid.copy(correctAnswer = "USD"))),
                "duplicate IDs" to Json.encodeToString(listOf(valid, valid)),
                "blank text" to Json.encodeToString(listOf(valid.copy(text = " "))),
                "duplicate options" to Json.encodeToString(listOf(valid.copy(options = listOf("JPY", "jpy")))),
                "missing fields" to """[{"id":"12345678-1234-1234-1234-123456789012"}]""",
            ) { (_, contents) ->
                withStorage(initialContent = contents) { storage ->
                    shouldThrow<IllegalArgumentException> { FileQuestionRepository(storage) }

                    Files.readString(storage) shouldBe contents
                }
            }
        }

        test("missing storage creates parent directories and persists the complete bundled quiz") {
            withDirectory { directory ->
                val storage = directory.resolve("nested").resolve("content").resolve("questions.json")
                val expected = seedQuestions()
                expected.size shouldBe 40
                Files.exists(storage) shouldBe false

                val repository = FileQuestionRepository(storage)

                Files.isRegularFile(storage) shouldBe true
                repository.all() shouldBe expected
                storedQuestions(storage) shouldBe expected
                FileQuestionRepository(storage).all() shouldBe expected
            }
        }

        context("blank storage is initialized from the bundled quiz") {
            withTests(mapOf("zero bytes" to "", "whitespace only" to " \t\r\n  \n")) { blank ->
                withStorage(initialContent = blank) { storage ->
                    val expected = seedQuestions()
                    val repository = FileQuestionRepository(storage)

                    repository.all() shouldBe expected
                    storedQuestions(storage) shouldBe expected
                }
            }
        }

        test("existing custom quiz content is retained without rewriting its file") {
            val custom = listOf(Question(UUID.randomUUID().toString(), "My own question?", listOf("JPY", "EUR"), "EUR"))
            val contents = "\n  ${Json.encodeToString(custom)}\n"
            withStorage(initialContent = contents) { storage ->
                FileQuestionRepository(storage).all() shouldBe custom
                Files.readString(storage) shouldBe contents
                FileQuestionRepository(storage).all() shouldBe custom
                Files.readString(storage) shouldBe contents
            }
        }

        test("an intentional empty array and deleting all seeded questions remain empty after restart") {
            withDirectory { directory ->
                val explicitEmpty = directory.resolve("intentionally-empty.json")
                Files.writeString(explicitEmpty, "[]")
                FileQuestionRepository(explicitEmpty).all() shouldBe emptyList()
                FileQuestionRepository(explicitEmpty).all() shouldBe emptyList()
                Files.readString(explicitEmpty) shouldBe "[]"

                val storage = directory.resolve("seeded-then-deleted.json")
                val repository = FileQuestionRepository(storage)
                val questions = repository.all()
                questions.size shouldBe 40
                questions.forEach { repository.delete(it.id) shouldBe true }

                repository.all() shouldBe emptyList()
                storedQuestions(storage) shouldBe emptyList()
                FileQuestionRepository(storage).all() shouldBe emptyList()
                FileQuestionRepository(storage).all() shouldBe emptyList()
            }
        }
    }

    private fun questionRequest(text: String = "Currency of Japan?") =
        CreateQuestionRequest(text, arrayOf("JPY", "EUR"), "JPY")

    private fun seedQuestions(): List<Question> {
        val resource = javaClass.getResourceAsStream("/questions-seed.json").shouldNotBeNull()
        return resource.bufferedReader().use { Json.decodeFromString(it.readText()) }
    }

    private fun storedQuestions(storage: Path): List<Question> = Json.decodeFromString(Files.readString(storage))

    private suspend fun withStorage(initialContent: String = "[]", block: suspend (Path) -> Unit) {
        withDirectory { directory ->
            val storage = directory.resolve("questions.json")
            Files.writeString(storage, initialContent)
            block(storage)
        }
    }

    private suspend fun withDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-repository-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
