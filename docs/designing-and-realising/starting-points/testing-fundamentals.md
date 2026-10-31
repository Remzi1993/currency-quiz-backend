# Testing Fundamentals

I use focused tests for individual rules and integration tests for the API components working together.

| Topic | Current evidence | Files |
|-------|------------------|-------|
| Unit tests | Settings tests accept valid origins and reject malformed values with `assertFailsWith`. This covers accepted input, rejected input and exceptions. | [ApiSettingsTest.kt](../../../src/test/kotlin/ApiSettingsTest.kt) |
| Structure and names | Behaviour-based names describe the expected result. The dotenv test arranges a file and properties, calls the loader, then asserts the parsed values: arrange–act–assert. | [LocalEnvironmentTest.kt](../../../src/test/kotlin/LocalEnvironmentTest.kt) |
| Integration tests | Ktor's `testApplication` exercises routing, authentication, JSON and temporary-file storage together. Contract tests also compare HTTP interactions with the shared specification. | [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt), [ContractTest.kt](../../../src/test/kotlin/ContractTest.kt) |
| Test framework and results | `kotlin.test` assertions run through Gradle with Ktor's test host. Failures identify the test and assertion; the HTML results report is generated under `build/reports/tests/test/`. | [build.gradle.kts](../../../build.gradle.kts) |
| Coverage | No coverage report is configured. Line or branch coverage would show which code ran, but cannot prove assertions are useful or requirements are complete. Interpreting an actual report remains missing evidence. | [build.gradle.kts](../../../build.gradle.kts) |

Run `./gradlew test` from the backend root (PowerShell 7+ and Bash). Use `./gradlew test --rerun` to execute the test task again when it is already up to date.

A unit test is enough for an isolated settings rule. Integration tests check boundaries such as route-to-storage behaviour. An end-to-end test would exercise the browser through the real API; these in-process API tests do not cover that level. The source alone does not establish a test-first workflow.
