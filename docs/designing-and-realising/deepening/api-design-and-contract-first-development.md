# API Design & Contract-First Development
- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/api-design-and-contract-first-development/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to assist in drafting this document by analysing my codebase, identifying 
  relevant implementations, and comparing my work against the AUAS BoKSA knowledge article. Codex helped structure and 
  formulate the documentation based on these findings. I independently reviewed, verified, and edited the generated 
  content to ensure it accurately reflects my work and understanding. I take full responsibility for the accuracy, 
  completeness, and quality of this document.

I want the Ktor backend and Vue/TypeScript frontend to agree on the API. 
Copying request and response models into both projects would make them easy to change independently. 
I chose one shared [OpenAPI contract](../../../shared/openapi/currency-quiz.v1.yaml) as the source for both languages.

Related starting point: [API Implementation](../starting-points/api-implementation.md).

## Why this approach

My RepTrackAI projects share Kotlin DTOs through a Git submodule. That works for Kotlin consumers, 
but a Vue application cannot directly import a Kotlin/JVM library. 
[OpenAPI](https://spec.openapis.org/oas/v3.0.3.html) describes HTTP operations independently of the implementation language.

| Approach | Fit for this quiz                                                                                        |
|----------|----------------------------------------------------------------------------------------------------------|
| REST     | Resource URLs and HTTP methods fit listing, creating, updating and deleting questions.                   |
| GraphQL  | Flexible client queries could help a more complex data model; the current responses are small and fixed. |
| SOAP     | XML messages and service contracts suit integrations that require that protocol.                         |
| RPC      | Operation calls fit command-based services; this API mainly manages question resources.                  |
| EDI      | Business-document exchange serves a different purpose from an interactive quiz.                          |

## One contract, two languages

The separate `currency-quiz-shared` repository is pinned under the backend's `shared/` submodule. 
Its `openapi/currency-quiz.v1.yaml` defines inputs, responses, errors and admin authentication.

The backend uses OpenAPI Generator 7.26.0 to generate kotlinx.serialization DTOs under `build/generated/openapi`. 
Resource processing also packages the canonical contract as `documentation.yaml` for Swagger.

The shared npm package uses [OpenAPI Generator and TypeScript](../../../shared/scripts/generate-typescript.mjs) to produce committed, declaration-only models. 
The frontend imports named types with `import type` from its own shared submodule. These provide compile-time checks; 
the frontend separately validates incoming JSON. Request validation and authorisation remain backend responsibilities.

Bearer clients and browsers both use stateless JWT verification. Browsers receive access and refresh JWTs in HttpOnly
cookies, and cookie writes additionally require a trusted Origin and JWT-bound CSRF header. Access defaults to five
minutes; each refresh to ten minutes from issuance. Refresh renews both JWT cookies with the same CSRF nonce, bounded
by an immutable absolute deadline that defaults to 24 hours from original sign-in. All three lifetimes are configurable.
Signed `auth_time` and `absolute_exp` claims preserve the original deadline across renewal; new tokens are shortened
near it. Metadata remains four fields, with `expiresIn` reporting the current refresh lifetime. No authentication session
or token registry is stored. Logout clears cookies, but earlier JWTs remain valid until their own expiry; replacement
does not provide reuse detection. Question content is stored in a JSON file.

## Versions and updates

`/api/v1` identifies the API's major URL version. `info.version` records the contract release using semantic versioning. 
The OpenAPI format version, such as `3.0.3`, is a separate value.

For a contract change, I update the shared specification, regenerate the types and review the diff. 
A renamed required field is a breaking change; it needs a migration plan and a new major API version. 
I commit the shared change first, then deliberately update each consumer's pin. 
The [shared README](../../../shared/README.md) describes the commands and workflow.

## Evidence and next steps

The [contract tests](../../../src/test/kotlin/ContractTest.kt) check real requests and 
responses against the bundled specification, reject public answer leaks and validate empty/null-only PATCH bodies. 
[Server tests](../../../src/test/kotlin/ServerTest.kt) cover CRUD, authentication, validation and error handling. 
Run `./gradlew test` from the backend root to rerun these checks (PowerShell 7+ and Bash). These are provider-side checks; 
they do not constitute consumer-driven Pact testing.

The Vue/TypeScript frontend now consumes the public quiz endpoints. 
The [shared repository is on GitHub](https://github.com/Remzi1993/currency-quiz-shared), 
and both applications include it as a submodule. 
Consumer-driven Pact testing and Postman/Bruno CI checks remain separate work.
