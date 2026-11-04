package info.remzi.currencyquiz

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe

class RequestValidationTest : FunSpec({
    context("question boundaries") {
        withTests(
            nameFn = { it.name },
            QuestionCase("one-character text", text = "x"),
            QuestionCase("500-character text", text = "x".repeat(500)),
            QuestionCase("padding does not count toward the text limit", text = "  ${"x".repeat(500)}\n"),
            QuestionCase("blank text", text = " \t\n", errors = listOf(TEXT_ERROR)),
            QuestionCase("501-character text", text = "x".repeat(501), errors = listOf(TEXT_ERROR)),
            QuestionCase("ten choices", options = (1..10).map { "choice $it" }, answer = "choice 10"),
            QuestionCase("one choice", options = listOf("JPY"), errors = listOf(OPTIONS_ERROR)),
            QuestionCase("no choices", options = emptyList(), errors = listOf(OPTIONS_ERROR, MATCH_ERROR)),
            QuestionCase("eleven choices", options = (1..11).map { "choice $it" }, answer = "choice 1", errors = listOf(OPTIONS_ERROR)),
            QuestionCase("one-character choice and answer", options = listOf("a", "b"), answer = "a"),
            QuestionCase("200-character choice and answer", options = listOf("x".repeat(200), "b"), answer = "x".repeat(200)),
            QuestionCase("choice padding is ignored", options = listOf("  JPY \n", "EUR"), answer = "\tjpy "),
            QuestionCase("blank choice", options = listOf("JPY", " \t"), errors = listOf(OPTION_ERROR)),
            QuestionCase("201-character choice", options = listOf("JPY", "x".repeat(201)), errors = listOf(OPTION_ERROR)),
            QuestionCase("duplicate choices ignore case and padding", options = listOf("JPY", " jPy "), errors = listOf(UNIQUE_ERROR)),
            QuestionCase("blank answer", answer = " \n", errors = listOf(ANSWER_ERROR, MATCH_ERROR)),
            QuestionCase("201-character answer", answer = "x".repeat(201), errors = listOf(ANSWER_ERROR, MATCH_ERROR)),
            QuestionCase("answer must match a choice", answer = "USD", errors = listOf(MATCH_ERROR)),
            QuestionCase("answer matching ignores case and padding", answer = " \tjPy\n"),
            QuestionCase("500-character accented text", text = "é".repeat(500)),
            QuestionCase("501-character accented text", text = "é".repeat(501), errors = listOf(TEXT_ERROR)),
        ) { row ->
            questionValidationErrors(row.text, row.options, row.answer) shouldBe row.errors
        }
    }

    test("validation reports all independent failures together") {
        questionValidationErrors(" ", listOf("", " "), "x".repeat(201)) shouldBe listOf(
            TEXT_ERROR, OPTION_ERROR, UNIQUE_ERROR, ANSWER_ERROR, MATCH_ERROR,
        )
    }

    context("answer normalization") {
        withTests(
            mapOf(
                "trims surrounding whitespace" to (" \tJPY\n " to "jpy"),
                "lowercases ASCII I consistently" to ("I" to "i"),
                "keeps internal whitespace" to ("  East Caribbean Dollar  " to "east caribbean dollar"),
                "does not strip accents" to (" ÉCU " to "écu"),
                "normalizes an empty answer" to (" \n\t" to ""),
            ),
        ) { (answer, expected) -> answer.normalizedAnswer() shouldBe expected }
    }

    context("question ID grammar") {
        withTests(
            mapOf(
                "canonical UUID" to ("01234567-89ab-cdef-0123-456789abcdef" to true),
                "uppercase UUID" to ("01234567-89AB-CDEF-0123-456789ABCDEF" to true),
                "nil UUID" to ("00000000-0000-0000-0000-000000000000" to true),
                "short groups that UUID.fromString accepts" to ("1-1-1-1-1" to false),
                "no separators" to ("0123456789abcdef0123456789abcdef" to false),
                "non-hexadecimal character" to ("g1234567-89ab-cdef-0123-456789abcdef" to false),
                "surrounding whitespace" to (" 01234567-89ab-cdef-0123-456789abcdef " to false),
                "trailing newline" to ("01234567-89ab-cdef-0123-456789abcdef\n" to false),
                "extra suffix" to ("01234567-89ab-cdef-0123-456789abcdef0" to false),
                "empty ID" to ("" to false),
            ),
        ) { (value, expected) -> isQuestionId(value) shouldBe expected }
    }
})

private const val TEXT_ERROR = "text must contain 1 to 500 characters"
private const val OPTIONS_ERROR = "options must contain 2 to 10 choices"
private const val OPTION_ERROR = "Each option must contain 1 to 200 characters"
private const val UNIQUE_ERROR = "options must be unique"
private const val ANSWER_ERROR = "correctAnswer must contain 1 to 200 characters"
private const val MATCH_ERROR = "correctAnswer must match one of the options"

private data class QuestionCase(
    val name: String,
    val text: String = "Which currency does Japan use?",
    val options: List<String> = listOf("JPY", "EUR"),
    val answer: String = "JPY",
    val errors: List<String> = emptyList(),
)
