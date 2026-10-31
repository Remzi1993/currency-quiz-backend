# Architecture and Design Patterns

The quiz uses a Vue client, a Ktor API and JSON-file storage. The shared OpenAPI contract connects the two languages at build time.

For a question request, the flow is: browser → Ktor route → repository → question data → public JSON response.

## ADR-001: Keep a layered API with replaceable storage

**Status:** Implemented; recorded retrospectively on 31 October 2026. This records the current design, without claiming earlier stakeholder approval.

**Context:** This is a small school project with public play and one configured admin. Storage and HTTP handling need to be testable without introducing several services.

**Decision:** Keep one Ktor application with separate routing, security, validation and persistence responsibilities. Startup creates a `FileQuestionRepository` and injects it through the `QuestionRepository` interface.

**Alternatives:** File operations inside route handlers would reduce abstraction but make storage failures harder to substitute in tests. A separate use-case layer could help with more business rules. Microservices, CQRS and an outbox would add deployment, messaging and consistency work without a current requirement.

**Consequences:** The repository boundary keeps file operations together and lets tests replace storage. The JSON implementation supports one backend process: its mutex and cached map cannot coordinate multiple replicas. Repository methods also accept API request DTOs, so this is pragmatic logical layering rather than strict hexagonal architecture.

This record uses the context, decision, status and consequences structure described by [Michael Nygard](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

## Implementation evidence

| Choice | Why it helps | Evidence |
|--------|--------------|----------|
| Composition at startup | One place chooses concrete dependencies and configures the application. | [Application.kt](../../../src/main/kotlin/Application.kt) |
| Repository and storage adapter | A collection-like interface separates callers from file operations, applying the [Repository idea](https://martinfowler.com/eaaCatalog/repository.html). | [QuestionRepository.kt](../../../src/main/kotlin/QuestionRepository.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt) |
| Manual dependency injection | A test supplies a failing repository and verifies the resulting safe `500` response. No live storage failure is needed. | [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) |
| Explicit response mapping | Public and admin responses are selected deliberately; public questions omit the correct answer. | [Models.kt](../../../src/main/kotlin/Models.kt) |

## Refactoring evidence and limits

Commit `8c681fa` replaced handwritten DTOs with generated contract models and removed the duplicate specification. This reduces duplicated definitions, while adding a generation dependency. Inspect it with `git show 8c681fa -- src/main/kotlin/Models.kt build.gradle.kts src/main/resources/documentation.yaml` (PowerShell 7+ and Bash).

The repository interface already existed in the prototype. This history therefore does not demonstrate a tightly coupled application being refactored into layers. Answer comparison still lives in routing, and the boundaries are not enforced through separate build modules.

The [contract tests](../../../src/test/kotlin/ContractTest.kt) and server tests validate API interactions; they do not exercise a browser against the deployed system. Run `./gradlew test` from the backend root (PowerShell 7+ and Bash).

For a non-technical reader: the website asks questions, the API checks answers and permissions, and storage keeps the quiz content. A database adapter would become relevant if multiple backend instances must share changes. Discussion and feedback from technical and non-technical stakeholders remain missing evidence.
