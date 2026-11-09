package info.remzi.currencyquiz

import at.favre.lib.crypto.bcrypt.BCrypt
import java.nio.CharBuffer

internal object PasswordHash {
    private val format = Regex("""^\$2[aby]\$(1[0-6])\$[./A-Za-z0-9]{53}$""")

    fun isValid(hash: String): Boolean = format.matches(hash)

    fun verifies(password: String, hash: String): Boolean {
        if (!isValid(hash)) return false
        val bytes = password.toByteArray(Charsets.UTF_8)
        return try {
            bytes.size in 1..72 && BCrypt.verifyer().verify(bytes, hash.toByteArray(Charsets.US_ASCII)).verified
        } finally {
            bytes.fill(0)
        }
    }

    fun hash(password: CharArray): String {
        require(password.size >= 12 && password.any { !it.isWhitespace() }) {
            "The password must contain at least 12 characters"
        }
        val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        try {
            require(bytes.remaining() <= 72) { "The password must not exceed 72 UTF-8 bytes" }
        } finally {
            bytes.array().fill(0)
        }
        return BCrypt.withDefaults().hashToString(12, password)
    }
}

/** Reads a console password or a single piped line; the password is never a command-line argument. */
fun main() {
    val password = System.console()?.readPassword()
        ?: System.`in`.bufferedReader(Charsets.UTF_8).readLine()?.toCharArray()
        ?: error("Supply a password on standard input")
    try {
        println(PasswordHash.hash(password))
    } finally {
        password.fill('\u0000')
    }
}
