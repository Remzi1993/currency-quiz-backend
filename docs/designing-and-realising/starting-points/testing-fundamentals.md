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
| Real backend browser smoke | Headless Chromium uses the Vue frontend and its Vite proxy against the packaged Ktor JAR, with disposable credentials, temporary JSON storage and environment-configured 2-second access / 6-second refresh / 18-second absolute lifetimes. | On 10 November 2026: five protected question creates passed across repeated renewal; both HttpOnly cookies changed while original signed login claims stayed fixed. Missing CSRF returned 403; the final refresh returned 401 after the absolute deadline. The UI required a fresh login and logout cleared both cookies. This was an ad hoc local smoke run, not a checked-in CI fixture. |
| Frontend browser suite | Six Playwright journeys cover public play and admin login, renewal, question management, expiry and logout. | The frontend suite passed against its owned fake API. That suite and the real-backend smoke establish different boundaries. |

The suite uses Kotest FunSpec and assertions on the JUnit Platform. MockK isolates repository behavior in routing tests;
real storage tests own temporary directories and remove them afterward. Prefer focused unit tests for isolated rules
and integration tests for boundaries. The frontend browser suite exercises user journeys rather than duplicating every unit case.

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
A separate deployed staging environment and production HTTPS verification remain future work.

The developer maintains tests with behavior changes, the reviewer checks rejection cases and contract compatibility,
and the deployment owner configures HTTPS, trusted origins, secrets and staging isolation. Use synthetic question data
and test-only password hashes; never copy production credentials or personal data into fixtures.

Frontend E2E tests own their test data and use stable accessible selectors and observable page/network state. The
real-backend smoke deliberately waited for the short, signed JWT deadlines to test actual expiry. Keep clocks and
data deterministic in the regular suite, run tests independently, and investigate flakes before adding retries.

## Entry and exit criteria

Entry: agree the token/CSRF contract and stateless logout limits, initialize the pinned shared contract, and reproduce
any reported authentication defect with a focused failing regression before changing its behavior.

Exit: relevant regressions pass, OpenAPI is valid, generated declarations are current, the full backend check passes,
and the existing JaCoCo gate reaches at least 90% line and 80% branch coverage for application code. Review compatibility
changes, dependency findings and remaining gaps before release. Local frontend/backend verification does not establish
that the deployed HTTPS configuration works.

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
