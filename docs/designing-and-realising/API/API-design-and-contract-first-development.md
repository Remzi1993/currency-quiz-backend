# API design & contract-first development

I want the Ktor backend and a future Vue/TypeScript frontend to agree on the API. Copying request and response models into both projects would make them easy to change independently. I chose one shared [OpenAPI contract](../../shared/openapi/currency-quiz.v1.yaml) as the source for both languages.

## Why this approach

My RepTrackAI projects share Kotlin DTOs through a Git submodule. That works for Kotlin consumers, but a Vue application cannot directly import a Kotlin/JVM library. [OpenAPI](https://spec.openapis.org/oas/v3.0.3.html) describes HTTP operations independently of the implementation language.

| Approach | Fit for this quiz                                                                                        |
|----------|----------------------------------------------------------------------------------------------------------|
| REST     | Resource URLs and HTTP methods fit listing, creating, updating and deleting questions.                   |
| GraphQL  | Flexible client queries could help a more complex data model; the current responses are small and fixed. |
| SOAP     | XML messages and service contracts suit integrations that require that protocol.                         |
| RPC      | Operation calls fit command-based services; this API mainly manages question resources.                  |
| EDI      | Business-document exchange serves a different purpose from an interactive quiz.                          |

## One contract, two languages

The separate `currency-quiz-shared` repository is pinned under the backend's `shared/` submodule. Its `openapi/currency-quiz.v1.yaml` defines inputs, responses, errors and admin authentication.

The backend uses OpenAPI Generator 7.26.0 to generate kotlinx.serialization DTOs under `build/generated/openapi`. Resource processing also packages the canonical contract as `documentation.yaml` for Swagger.

The shared npm package uses openapi-typescript 7.13.0 to generate committed TypeScript declarations. The future frontend can use `import type` from its submodule or a local package dependency. These are [compile-time types](https://openapi-ts.dev/introduction), not a runtime validator or HTTP client. Validation and authorisation remain backend responsibilities.

JWT verification avoids a server-side login session. The application still has state: question content is stored in a JSON file. Stateless authentication does not make persistence unnecessary.

## Versions and updates

`/api/v1` identifies the API's major URL version. `info.version` records the contract release using semantic versioning. The OpenAPI format version, such as `3.0.3`, is a separate value.

For a contract change, I update the shared specification, regenerate the types and review the diff. A renamed required field is a breaking change; it needs a migration plan and a new major API version. I commit the shared change first, then deliberately update each consumer's pin. The [shared README](../../shared/README.md) describes the commands and workflow.

## Evidence and next steps

All 35 backend tests and `shadowJar` have passed, including contract tests for requests, responses, rejected public answer leaks and empty/null-only PATCH bodies. Shared-contract linting, TypeScript generation and the example consumer's type check also passed. The Docker image builds successfully; its running API serves the exact shared specification, seeds 40 public questions and accepts admin login. These checks provide backend evidence; they do not constitute consumer-driven Pact testing.

The frontend has not been created. Postman/Bruno CI checks are also pending. The shared repository is local only, so publishing its remote is required before other machines can clone the submodule normally.
