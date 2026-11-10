# Testing Fundamentals
- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/testing-fundamentals/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to assist in drafting this document by analysing my codebase, identifying
  relevant implementations, and comparing my work against the AUAS BoKSA knowledge article. Codex helped structure and
  formulate the documentation based on these findings. I independently reviewed, verified, and edited the generated
  content to ensure it accurately reflects my work and understanding. I take full responsibility for the accuracy,
  completeness, and quality of this document.

## Test strategy

This strategy covers the backend authentication change and question API. The highest risks are unauthorized question
changes, refresh JWTs being accepted as access, missing CSRF/Origin checks, renewal extending the original login deadline, credential leakage
and accidental reintroduction of backend authentication state.

| Test level | Scope and purpose | Existing evidence |
|---|---|---|
| Unit | Configuration boundaries, salted password verification, JWT signature/claims/purpose/expiry, rolling refresh, the absolute login deadline, input rules and cookie policy. Controlled clocks make expiry tests deterministic. | Settings, auth, JWT, browser-token and validation specs under src/test/kotlin. |
| Component/integration | Ktor routing, auth providers, JSON, Origin/CSRF, rate limiting and real temporary-file persistence together. Independent application instances establish stateless token validation. | ServerTest, BrowserSessionTest, StatelessAuthTest and repository tests. |
| API contract | Compare actual HTTP requests and responses with the authoritative OpenAPI file; verify public responses omit correct answers. Generated TypeScript examples check consumer model compatibility. | ContractTest and shared/examples/typescript-contract.ts. |
| Previous packaged API smoke | The earlier fixed-refresh implementation ran over real loopback HTTP with isolated temporary storage, disposable credentials and 2-second access / 6-second refresh lifetimes. | On 9 November 2026: login 200, missing CSRF 403, authorized writes 201, access expiry 401, refresh 200 and final refresh expiry 401. Late access renewal ended at the original refresh deadline. The server and temporary data were removed afterward. This historical run does not establish the new rolling-refresh behavior. |
| Browser E2E | Intended small set: browser login, protected edit, access refresh, expired-refresh login and logout cookie clearing through the frontend proxy. | Not implemented or run in this backend change. In-process API tests are not browser E2E tests. |

The suite uses Kotest FunSpec and assertions on the JUnit Platform. MockK isolates repository behavior in routing tests;
real storage tests own temporary directories and remove them afterward. Prefer focused unit tests for isolated rules
and integration tests for boundaries. A small browser suite should complete the pyramid, not duplicate every unit case.

The current token policy uses JWT_TOKEN_TTL_SECONDS=300, JWT_REFRESH_TOKEN_TTL_SECONDS=600 and
JWT_ABSOLUTE_TTL_SECONDS=86400 by default. Each successful refresh issues both JWTs again, while preserving the
signed original auth_time and absolute_exp. Tests cover expiry when a refresh is not renewed within its current
10-minute lifetime, 144 renewals within a simulated day, shorter final tokens and rejection at the exact 24-hour
deadline. The server retains no token registry, so tests also preserve the documented limit that an older refresh
JWT remains usable until its own expiry.

## Environments, data and responsibilities

Each backend test owns settings, test credentials, clocks and temporary storage. It never reads the developer's .env,
contacts production, or shares mutable data with another test. This provides an isolated test environment while
exercising the same application wiring. CI uses JDK25 and runs contract validation, tests, coverage gates and packaging.
A separate deployed staging environment and realistic browser/proxy test run remain future work.

The developer maintains tests with behavior changes, the reviewer checks rejection cases and contract compatibility,
and the deployment owner configures HTTPS, trusted origins, secrets and staging isolation. Use synthetic question data
and test-only password hashes; never copy production credentials or personal data into fixtures.

For future browser E2E tests, create and delete data through test-only authenticated setup/cleanup, use stable accessible
selectors, and wait for observable page/network state rather than fixed sleeps. Keep clocks and data deterministic,
run tests independently, and investigate flakes before adding retries. These are planned practices, not completed evidence.

## Entry and exit criteria

Entry: agree the token/CSRF contract and stateless logout limits, initialize the pinned shared contract, and reproduce
any reported authentication defect with a focused failing regression before changing its behavior.

Exit: relevant regressions pass, OpenAPI is valid, generated declarations are current, the full backend check passes,
and the existing JaCoCo gate reaches at least 90% line and 80% branch coverage for application code. Review compatibility
changes, dependency findings and remaining gaps before release. Frontend migration and browser E2E remain required
before claiming the complete deployed flow works.

Run the backend gates in PowerShell 7+:

```powershell
./gradlew.bat check
```

In Linux/Bash:

```bash
./gradlew check
```

The following shared-contract command works in both shells:

```sh
npm --prefix shared run check
```

## Interpreting test quality

JaCoCo writes HTML/XML reports under build/reports/jacoco/test. Line coverage means a line executed; branch coverage
means conditional outcomes executed. Neither proves assertions detect incorrect behavior. The build excludes generated
DTOs from the application coverage gate, while contract tests check their serialized boundary.

PIT mutation testing is not configured or run. A mutation score would measure the proportion of generated code changes
the tests detect, subject to equivalent mutants and exclusions; it is not a security score. Targeted next improvements
are browser/proxy E2E coverage and mutation analysis of JWT-purpose, expiry and CSRF checks. The OpenAPI boundary checks
reduce integration risk earlier than E2E, but they are provider contract tests, not consumer-driven Pact tests.
