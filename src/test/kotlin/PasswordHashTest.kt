package info.remzi.currencyquiz

import at.favre.lib.crypto.bcrypt.BCrypt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class PasswordHashTest : FunSpec({
    val password = "test-admin-password-123"
    val hash = BCrypt.withDefaults().hashToString(10, password.toCharArray())

    test("hashes include a fresh salt and the default work factor") {
        val first = PasswordHash.hash(password.toCharArray())
        val second = PasswordHash.hash(password.toCharArray())

        first shouldNotBe second
        first.shouldStartWith("\$2a\$12\$")
        PasswordHash.isValid(first) shouldBe true
        PasswordHash.verifies(password, first) shouldBe true
        PasswordHash.verifies(password, second) shouldBe true
        PasswordHash.verifies("incorrect-password", first) shouldBe false
    }

    context("supported bcrypt variants authenticate the same password") {
        withTests("2a", "2b", "2y") { version ->
            val variant = hash.replaceRange(1, 3, version)

            PasswordHash.isValid(variant) shouldBe true
            PasswordHash.verifies(password, variant) shouldBe true
            PasswordHash.verifies(password.uppercase(), variant) shouldBe false
        }
    }

    context("invalid hashes and unsafe work factors are rejected before verification") {
        withTests(
            mapOf(
                "plaintext" to password,
                "empty" to "",
                "unsupported version" to hash.replaceRange(1, 3, "2x"),
                "too little work" to hash.replaceRange(4, 6, "09"),
                "too much work" to hash.replaceRange(4, 6, "17"),
                "missing character" to hash.dropLast(1),
                "extra character" to (hash + "a"),
                "invalid alphabet" to (hash.dropLast(1) + "!"),
                "surrounding whitespace" to " $hash ",
                "line break" to "$hash\n",
            ),
        ) { candidate ->
            PasswordHash.isValid(candidate) shouldBe false
            PasswordHash.verifies(password, candidate) shouldBe false
        }
    }

    test("the upper configured work factor is accepted without performing expensive verification") {
        PasswordHash.isValid(hash.replaceRange(4, 6, "16")) shouldBe true
    }

    test("passwords at 72 UTF-8 bytes work but longer passwords never silently truncate") {
        val boundary = "é".repeat(36)
        val boundaryHash = BCrypt.withDefaults().hashToString(10, boundary.toCharArray())

        PasswordHash.verifies(boundary, boundaryHash) shouldBe true
        PasswordHash.verifies(boundary + "a", boundaryHash) shouldBe false
        PasswordHash.verifies("e".repeat(72), boundaryHash) shouldBe false
        PasswordHash.verifies("", boundaryHash) shouldBe false
    }

    context("the hash tool rejects short blank or oversized passwords") {
        withTests(
            mapOf(
                "too short" to "a".repeat(11),
                "blank" to " ".repeat(12),
                "too many ASCII bytes" to "a".repeat(73),
                "too many UTF-8 bytes" to "é".repeat(37),
            ),
        ) { candidate ->
            shouldThrow<IllegalArgumentException> { PasswordHash.hash(candidate.toCharArray()) }
        }
    }
})
