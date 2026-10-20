package info.remzi.currencyquiz

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.DotenvException
import io.github.cdimascio.dotenv.dotenv
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val applicationEnvironmentKeys = setOf(
    "ADMIN_USERNAME", "ADMIN_PASSWORD", "JWT_SECRET", "JWT_ISSUER", "JWT_AUDIENCE",
    "JWT_TOKEN_TTL_SECONDS", "CORS_ALLOWED_ORIGINS", "QUESTIONS_FILE", "PORT",
)

/** Ktor's YAML substitutions read JVM properties as well as OS environment variables. */
internal fun loadLocalEnvironment(
    directory: Path = Path.of("."),
    environment: Map<String, String> = System.getenv(),
    properties: Properties = System.getProperties(),
) {
    val absoluteDirectory = directory.toAbsolutePath().normalize()
    if (!Files.exists(absoluteDirectory.resolve(".env"))) return

    val localEnvironment = try {
        dotenv {
            this.directory = absoluteDirectory.toString()
        }
    } catch (_: DotenvException) {
        // Parser exceptions can contain the offending line, including a secret.
        throw IllegalStateException("Cannot read .env; check the file permissions and KEY=value syntax.")
    }
    for (entry in localEnvironment.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
        if (entry.key in applicationEnvironmentKeys && entry.key !in environment && properties.getProperty(entry.key) == null) {
            properties.setProperty(entry.key, entry.value)
        }
    }
}
