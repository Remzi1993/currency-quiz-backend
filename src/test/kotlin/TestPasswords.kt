package info.remzi.currencyquiz

import at.favre.lib.crypto.bcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

private val passwordHashes = ConcurrentHashMap<String, String>()

/** Test-only hashes use bcrypt's minimum accepted work factor and are shared across fixtures. */
internal fun testPasswordHash(password: String): String = passwordHashes.computeIfAbsent(password) {
    BCrypt.withDefaults().hashToString(10, it.toCharArray())
}
