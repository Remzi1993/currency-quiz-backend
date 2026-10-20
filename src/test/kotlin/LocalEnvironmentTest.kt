package info.remzi.currencyquiz

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class LocalEnvironmentTest {
    @Test
    fun `local environment parses documented keys quotes and comments`() = withDirectory { directory ->
        Files.writeString(directory.resolve(".env"), """
            # Development settings
            ADMIN_USERNAME="local-admin"
            ADMIN_PASSWORD="a password with # inside"
            JWT_SECRET="a-local-signing-secret-with-at-least-32-bytes"
            JWT_ISSUER=local-issuer
            JWT_AUDIENCE=local-audience
            JWT_TOKEN_TTL_SECONDS=1800
            CORS_ALLOWED_ORIGINS="http://localhost:5173,https://quiz.example"
            QUESTIONS_FILE="data/local questions.json"
            PORT=9090 # Development port
        """.trimIndent())
        val properties = Properties()

        loadLocalEnvironment(directory, emptyMap(), properties)

        assertEquals(9, properties.size)
        assertEquals("local-admin", properties.getProperty("ADMIN_USERNAME"))
        assertEquals("a password with # inside", properties.getProperty("ADMIN_PASSWORD"))
        assertEquals("a-local-signing-secret-with-at-least-32-bytes", properties.getProperty("JWT_SECRET"))
        assertEquals("local-issuer", properties.getProperty("JWT_ISSUER"))
        assertEquals("local-audience", properties.getProperty("JWT_AUDIENCE"))
        assertEquals("1800", properties.getProperty("JWT_TOKEN_TTL_SECONDS"))
        assertEquals("http://localhost:5173,https://quiz.example", properties.getProperty("CORS_ALLOWED_ORIGINS"))
        assertEquals("data/local questions.json", properties.getProperty("QUESTIONS_FILE"))
        assertEquals("9090", properties.getProperty("PORT"))
    }

    @Test
    fun `existing environment and JVM properties take precedence including blank values`() = withDirectory { directory ->
        Files.writeString(directory.resolve(".env"), """
            ADMIN_USERNAME=local-admin
            ADMIN_PASSWORD=local-password
            JWT_SECRET=local-secret
            PORT=9090
            JWT_AUDIENCE=local-audience
        """.trimIndent())
        val environment = mapOf("ADMIN_USERNAME" to "deployed-admin", "ADMIN_PASSWORD" to "")
        val properties = Properties().apply {
            setProperty("JWT_SECRET", "existing-jvm-secret")
            setProperty("PORT", "")
        }

        loadLocalEnvironment(directory, environment, properties)

        assertNull(properties.getProperty("ADMIN_USERNAME"))
        assertNull(properties.getProperty("ADMIN_PASSWORD"))
        assertEquals("existing-jvm-secret", properties.getProperty("JWT_SECRET"))
        assertEquals("", properties.getProperty("PORT"))
        assertEquals("local-audience", properties.getProperty("JWT_AUDIENCE"))
        assertEquals("deployed-admin", environment["ADMIN_USERNAME"])
        assertEquals("", environment["ADMIN_PASSWORD"])
    }

    @Test
    fun `missing local file leaves properties unchanged`() = withDirectory { directory ->
        val properties = Properties().apply { setProperty("PORT", "8081") }

        loadLocalEnvironment(directory, emptyMap(), properties)

        assertEquals(setOf("PORT"), properties.stringPropertyNames())
        assertEquals("8081", properties.getProperty("PORT"))
    }

    @Test
    fun `unrecognized dotenv keys never become JVM properties`() = withDirectory { directory ->
        Files.writeString(directory.resolve(".env"), """
            java.home=/invalid-java-runtime
            user.home=/invalid-home
            JAVA_TOOL_OPTIONS=-javaagent:untrusted.jar
            UNRELATED_SECRET=do-not-import
            PORT=9090
        """.trimIndent())
        val properties = Properties().apply { setProperty("user.home", "existing-home") }

        loadLocalEnvironment(directory, emptyMap(), properties)

        assertEquals(setOf("user.home", "PORT"), properties.stringPropertyNames())
        assertEquals("existing-home", properties.getProperty("user.home"))
        assertEquals("9090", properties.getProperty("PORT"))
    }

    @Test
    fun `loader reads only the requested directory`() = withDirectory { directory ->
        Files.writeString(directory.resolve(".env"), "PORT=9090")
        val child = Files.createDirectory(directory.resolve("child"))
        val properties = Properties()

        loadLocalEnvironment(child, emptyMap(), properties)

        assertTrue(properties.isEmpty())
    }

    @Test
    fun `malformed dotenv errors do not expose secret lines or parser causes`() = withDirectory { directory ->
        val secret = "secret-that-must-never-appear-in-the-error"
        Files.writeString(directory.resolve(".env"), "PORT=9090\nJWT_SECRET $secret")
        val properties = Properties()

        val failure = assertFailsWith<IllegalStateException> {
            loadLocalEnvironment(directory, emptyMap(), properties)
        }

        assertEquals("Cannot read .env; check the file permissions and KEY=value syntax.", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(secret))
        assertTrue(properties.isEmpty())
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
