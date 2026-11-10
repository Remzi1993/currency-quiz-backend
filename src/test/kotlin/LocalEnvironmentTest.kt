package info.remzi.currencyquiz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class LocalEnvironmentTest : FunSpec() {
    init {
        test("local environment parses every documented key including quotes and comments") {
            withDirectory { directory ->
                Files.writeString(directory.resolve(".env"), """
                    # Development settings
                    ADMIN_USERNAME="local-admin"
                    ADMIN_PASSWORD_HASH="a hash with # inside"
                    JWT_SECRET="a-local-signing-secret-with-at-least-32-bytes"
                    JWT_ISSUER=local-issuer
                    JWT_AUDIENCE=local-audience
                    JWT_TOKEN_TTL_SECONDS=300
                    JWT_REFRESH_TOKEN_TTL_SECONDS=600
                    CORS_ALLOWED_ORIGINS="http://localhost:5173,https://quiz.example"
                    ADMIN_CORS_ALLOWED_ORIGINS="http://localhost:5173,https://quiz.example"
                    AUTH_COOKIE_SECURE=false
                    AUTH_COOKIE_SAME_SITE=Lax
                    QUESTIONS_FILE="data/local questions.json"
                    PORT=9090 # Development port
                """.trimIndent())
                val properties = Properties()

                loadLocalEnvironment(directory, emptyMap(), properties)

                properties.toMap() shouldBe mapOf(
                    "ADMIN_USERNAME" to "local-admin",
                    "ADMIN_PASSWORD_HASH" to "a hash with # inside",
                    "JWT_SECRET" to "a-local-signing-secret-with-at-least-32-bytes",
                    "JWT_ISSUER" to "local-issuer",
                    "JWT_AUDIENCE" to "local-audience",
                    "JWT_TOKEN_TTL_SECONDS" to "300",
                    "JWT_REFRESH_TOKEN_TTL_SECONDS" to "600",
                    "CORS_ALLOWED_ORIGINS" to "http://localhost:5173,https://quiz.example",
                    "ADMIN_CORS_ALLOWED_ORIGINS" to "http://localhost:5173,https://quiz.example",
                    "AUTH_COOKIE_SECURE" to "false",
                    "AUTH_COOKIE_SAME_SITE" to "Lax",
                    "QUESTIONS_FILE" to "data/local questions.json",
                    "PORT" to "9090",
                )
            }
        }

        test("existing environment and JVM properties take precedence including blank values") {
            withDirectory { directory ->
                Files.writeString(directory.resolve(".env"), """
                    ADMIN_USERNAME=local-admin
                    ADMIN_PASSWORD_HASH=local-password
                    JWT_SECRET=local-secret
                    PORT=9090
                    JWT_AUDIENCE=local-audience
                """.trimIndent())
                val environment = mapOf("ADMIN_USERNAME" to "deployed-admin", "ADMIN_PASSWORD_HASH" to "")
                val properties = Properties().apply {
                    setProperty("JWT_SECRET", "existing-jvm-secret")
                    setProperty("PORT", "")
                }

                loadLocalEnvironment(directory, environment, properties)

                properties.getProperty("ADMIN_USERNAME").shouldBeNull()
                properties.getProperty("ADMIN_PASSWORD_HASH").shouldBeNull()
                properties.toMap() shouldBe mapOf(
                    "JWT_SECRET" to "existing-jvm-secret",
                    "PORT" to "",
                    "JWT_AUDIENCE" to "local-audience",
                )
                environment shouldBe mapOf("ADMIN_USERNAME" to "deployed-admin", "ADMIN_PASSWORD_HASH" to "")
            }
        }

        test("bcrypt dollar separators survive unquoted and quoted dotenv values unchanged") {
            val hash = testPasswordHash("dotenv-test-password-123")
            for (quote in listOf("", "'", "\"")) {
                withDirectory { directory ->
                    Files.writeString(directory.resolve(".env"), "ADMIN_PASSWORD_HASH=$quote$hash$quote")
                    val properties = Properties()

                    loadLocalEnvironment(directory, emptyMap(), properties)

                    properties.getProperty("ADMIN_PASSWORD_HASH") shouldBe hash
                }
            }
        }

        test("literal apostrophes in other double-quoted settings are preserved") {
            withDirectory { directory ->
                Files.writeString(directory.resolve(".env"), """JWT_SECRET="'literal-signing-secret-with-at-least-thirty-two-bytes'"""")
                val properties = Properties()

                loadLocalEnvironment(directory, emptyMap(), properties)

                properties.getProperty("JWT_SECRET") shouldBe "'literal-signing-secret-with-at-least-thirty-two-bytes'"
            }
        }

        test("missing local file leaves properties unchanged") {
            withDirectory { directory ->
                val properties = Properties().apply { setProperty("PORT", "8081") }

                loadLocalEnvironment(directory, emptyMap(), properties)

                properties.toMap() shouldBe mapOf("PORT" to "8081")
            }
        }

        test("unrecognized dotenv keys never become JVM properties") {
            withDirectory { directory ->
                Files.writeString(directory.resolve(".env"), """
                    java.home=/invalid-java-runtime
                    user.home=/invalid-home
                    JAVA_TOOL_OPTIONS=-javaagent:untrusted.jar
                    UNRELATED_SECRET=do-not-import
                    ADMIN_PASSWORD=legacy-plaintext-password
                    ADMIN_SESSION_IDLE_SECONDS=900
                    ADMIN_SESSION_ABSOLUTE_SECONDS=1800
                    PORT=9090
                """.trimIndent())
                val properties = Properties().apply { setProperty("user.home", "existing-home") }

                loadLocalEnvironment(directory, emptyMap(), properties)

                properties.toMap() shouldBe mapOf("user.home" to "existing-home", "PORT" to "9090")
            }
        }

        test("loader reads only the requested directory") {
            withDirectory { directory ->
                Files.writeString(directory.resolve(".env"), "PORT=9090")
                val child = Files.createDirectory(directory.resolve("child"))
                val properties = Properties()

                loadLocalEnvironment(child, emptyMap(), properties)

                properties.isEmpty() shouldBe true
            }
        }

        test("malformed dotenv errors do not expose secret lines or parser causes or partially apply values") {
            withDirectory { directory ->
                val secret = "secret-that-must-never-appear-in-the-error"
                Files.writeString(directory.resolve(".env"), "PORT=9090\nJWT_SECRET $secret")
                val properties = Properties()

                val failure = shouldThrow<IllegalStateException> {
                    loadLocalEnvironment(directory, emptyMap(), properties)
                }

                failure.message shouldBe "Cannot read .env; check the file permissions and KEY=value syntax."
                failure.cause.shouldBeNull()
                failure.toString() shouldNotContain secret
                properties.isEmpty() shouldBe true
            }
        }

        test("reloading leaves existing properties alone and independent loads read the latest file") {
            withDirectory { directory ->
                val file = directory.resolve(".env")
                Files.writeString(file, "PORT=9090\nJWT_ISSUER=first")
                val properties = Properties()
                loadLocalEnvironment(directory, emptyMap(), properties)
                Files.writeString(file, "PORT=9091\nJWT_ISSUER=second\nJWT_AUDIENCE=new-audience")

                loadLocalEnvironment(directory, emptyMap(), properties)

                properties.toMap() shouldBe mapOf("PORT" to "9090", "JWT_ISSUER" to "first", "JWT_AUDIENCE" to "new-audience")
                val independentProperties = Properties()
                loadLocalEnvironment(directory, emptyMap(), independentProperties)
                independentProperties.toMap() shouldBe mapOf("PORT" to "9091", "JWT_ISSUER" to "second", "JWT_AUDIENCE" to "new-audience")
            }
        }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("currencyquiz-local-env-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
