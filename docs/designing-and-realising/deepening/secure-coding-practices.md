# Secure Coding Practices

- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/secure-coding-practices/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to help analyse the current code and tests, check security references,
  and update the explanations and evidence below. I remain responsible for reviewing the content and explaining
  these decisions myself.

## My goal and choices

Currency Quiz is my personal side project for school, and I am its only developer. Players can answer questions
without an account; only the configured administrator may change content. Avoiding player registration removes
the need for profiles, password recovery and stored player histories that the current quiz does not require.

I use JWTs for authentication and verify the configured subject and admin role for authorization. The backend
maintains no registry of authentication sessions, tokens or activity. This avoids maintaining a session store, with
the revocation limits described below. Routine request logs and transient IP-based rate limits still exist.

Browsers use access and refresh JWTs in HttpOnly cookies. Bearer API clients receive an access JWT in JSON.
Both transports use the same signature policy, with distinct checks for access and refresh purposes. There is
one configured administrator and no registration flow. The
[architecture decisions](architecture-and-design-patterns.md) explain the alternatives and maintenance tradeoffs.

## Security in the development process

The [OWASP guidance](https://devguide.owasp.org/en/02-foundations/02-secure-development/) places security throughout
the development lifecycle. For authentication changes, my Definition of Done is:

- Identify affected secrets, trust boundaries and possible misuse before implementation.
- Cover permitted requests and rejection cases, including token purpose, expiry, Origin and CSRF.
- Review the change against the [OWASP secure code review checklist](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html).
- Run contract validation, tests and coverage gates; record dependency findings and remaining risks.

A peer review, independent penetration test and completed OWASP vulnerability labs remain separate evidence gaps.

## What the implementation shows

| Concern | Current measure and why | Code evidence |
|---|---|---|
| Unauthorised changes | Verified access JWT with the configured subject and admin role. Refresh JWTs cannot authorize question changes. | [JwtTokens.kt](../../../src/main/kotlin/JwtTokens.kt), [Security.kt](../../../src/main/kotlin/Security.kt) |
| Password storage | Salted bcrypt verifier in configuration replaces the plaintext password; its work factor increases the cost of guessing. | [PasswordHash.kt](../../../src/main/kotlin/PasswordHash.kt), [ApiSettings.kt](../../../src/main/kotlin/ApiSettings.kt) |
| Cookie request forgery | Exact Origin allowlist and a random nonce bound to signed JWT claims, compared with the CSRF header in constant time. | [BrowserTokens.kt](../../../src/main/kotlin/BrowserTokens.kt) |
| Invalid input | Strict JSON, bounded fields and validation after merging PATCH fields prevent invalid persisted questions. Generated DTOs alone do not enforce those rules. | [RequestValidation.kt](../../../src/main/kotlin/RequestValidation.kt), [Serialization.kt](../../../src/main/kotlin/Serialization.kt) |
| Secret exposure | Settings and issued-token objects avoid generated string representations containing secrets. Dotenv parser errors omit input lines and causes; routine request logs omit credentials. | [ApiSettings.kt](../../../src/main/kotlin/ApiSettings.kt), [JwtTokens.kt](../../../src/main/kotlin/JwtTokens.kt), [LocalEnvironment.kt](../../../src/main/kotlin/LocalEnvironment.kt), [Application.kt](../../../src/main/kotlin/Application.kt) |
| Excessive public data | Public questions omit correct answers; answer checking happens on the server. | [Models.kt](../../../src/main/kotlin/Models.kt) |
| Abuse and error leakage | Process-local connection-IP limits on login, refresh and answers. Generic client errors omit exception details. | [RateLimiting.kt](../../../src/main/kotlin/RateLimiting.kt), [StatusPages.kt](../../../src/main/kotlin/StatusPages.kt) |
| Runtime privileges and packaged secrets | The container runs as a dedicated non-root user; the Docker context excludes local environment files and mutable question data. | [Dockerfile](../../../Dockerfile), [.dockerignore](../../../.dockerignore) |

## Passwords and configuration

`PasswordHash` uses a dedicated bcrypt library. The hash utility generates a fresh random salt with cost 12 and
requires at least 12 characters, some non-whitespace content, and at most 72 UTF-8 bytes. Verification rejects
longer passwords rather than silently accepting a matching 72-byte prefix. Startup accepts supported bcrypt
hashes with costs from 10 through 16 and requires a signing secret of at least 32 UTF-8 bytes. The byte-length check
does not establish randomness; the signing secret must be generated securely, as shown in the
[configuration instructions](../../../README.md#configuration).

The current implementation uses bcrypt. [OWASP's password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
explains salts, work factors and bcrypt's input limit, and recommends Argon2id for new systems. An algorithm change
would require a separate compatibility and migration decision.

`AuthService` compares credentials exactly, without trimming or changing case. It checks the bcrypt verifier even
for an unknown username and returns the same invalid-credentials response. The SHA-256 digest in this class is
used for username comparison; the password verifier is bcrypt. These controls reduce obvious differences between
failed credential checks, without establishing that every request has identical timing.

Only the documented application keys are loaded from `.env`, and existing OS environment variables and JVM
properties take precedence. Single quotes around `ADMIN_PASSWORD_HASH` preserve its dollar separators in Compose;
the native loader removes those surrounding quotes only for that setting. Malformed dotenv input fails before
applying values and omits the parser's secret-bearing line and cause. Local secrets belong in `.env` or the hosting
environment, outside Git and container images. The [README](../../../README.md#configuration) documents hash generation
through standard input so the password need not appear in command-line arguments.

## Stateless JWTs in HttpOnly cookies

Browser requests use `credentials: include`. Despite the `/session` route name, the backend stores no session record.

| Operation | Credential and checks | Result |
|---|---|---|
| `POST /api/v1/auth/login` | Username/password and the shared login rate limit. | Access JWT in JSON for bearer clients; no browser cookies. |
| `POST /api/v1/auth/session` | Username/password, an exact trusted Origin and the same login limit. | Both JWT cookies and four metadata fields. |
| `GET /api/v1/auth/session` | A valid refresh or access cookie. Origin may be absent; an explicit untrusted Origin is rejected. | Metadata only; no tokens are issued and no expiry is extended. |
| `POST /api/v1/auth/session/refresh` | Valid refresh cookie, trusted Origin, matching `X-CSRF-Token` and the refresh rate limit. | Both JWT cookies are reissued within the original login deadline. |
| Question create, update and delete | Valid access JWT. Cookie authentication also requires trusted Origin and matching CSRF header. | Authorized question mutation. A refresh JWT cannot authorize it. |
| `DELETE /api/v1/auth/session` | Valid refresh cookie, falling back to valid access, plus trusted Origin and matching CSRF header. | Both original cookie scopes are cleared. |

The cookies are host-only and HttpOnly, with Secure and SameSite=Strict defaults. `currency_quiz_admin` uses
`/api/v1`; `currency_quiz_admin_refresh` uses `/api/v1/auth/session`, so the browser does not normally send the refresh
cookie to question routes. This narrows routine exposure of the more powerful renewal credential.

The browser metadata fields are `username`, `expiresIn`, `accessExpiresIn` and `csrfToken`; neither JWT is returned
in the browser JSON response. `expiresIn` is the remaining verified refresh lifetime when available, otherwise the
access lifetime. It is not the time remaining until the original absolute deadline. `accessExpiresIn` is zero when
there is no valid matching access credential. Metadata can therefore remain available after access expiry while
the refresh cookie is still valid.

### Rolling renewal and the absolute deadline

| Setting | Default | Purpose |
|---|---|---|
| `JWT_TOKEN_TTL_SECONDS` | 300 seconds | Short access lifetime limits use of a copied access credential. |
| `JWT_REFRESH_TOKEN_TTL_SECONDS` | 600 seconds from issuance | Permit continued work through explicit renewal before the current refresh expires. |
| `JWT_ABSOLUTE_TTL_SECONDS` | 86,400 seconds (24 hours) | Require a fresh login after a bounded period from original sign-in. |

All three accept positive integer seconds up to 2,147,483,647, with access lifetime no longer than refresh and
refresh no longer than the absolute limit. Longer configured lifetimes increase the exposure window.

Both JWTs carry signed `auth_time` and `absolute_exp` claims. Login establishes them once; successful renewal copies
them unchanged along with the CSRF nonce. Each new expiry is the earlier of issuance time plus its configured
lifetime and the original absolute deadline. A refresh with only 20 seconds of its own lifetime left can still
receive fresh 300/600-second tokens when the absolute deadline is sufficiently far away. With the default lifetimes,
when only 20 seconds remain before that absolute deadline, both new tokens expire in 20 seconds.

A fresh sign-in is required at the absolute deadline, or earlier if the current refresh JWT expires without renewal.
Ordinary requests and metadata reads do not refresh credentials. There is no activity endpoint or separate
inactivity timer, so making requests alone does not keep a login alive.

Verification fixes HMAC-SHA256 and checks issuer, audience, configured subject, admin role, issue time, expiry,
token purpose and original login deadline. Access requires `typ=at+jwt` and `token_use=access`; refresh requires
`typ=refresh+jwt` and `token_use=refresh`. Login-time claims must be integers in the allowed order and within the
configured absolute lifetime; malformed or older JWTs without them require a fresh sign-in. Tokens are rejected
at their expiry boundary. Issuance uses one clock snapshot per pair, and renewal rechecks expiry during handling.

The CSRF nonce uses 32 random bytes from `SecureRandom` and is bound to the signed claims. Possession of that value
alone cannot authenticate a request. Signed JWT payloads remain readable to anyone holding them, so they contain
the admin identity and policy claims rather than passwords or signing secrets.

### Limits of stateless authentication

Logout removes browser cookies; it cannot invalidate a copied JWT. A refresh JWT can be reused until its own
expiry. There is no single-use refresh enforcement, replay detection or individual token revocation. Reissuing a JWT
does not invalidate its predecessor. These are explicit limits of removing server-side authentication state;
[OWASP's JWT guidance](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html) discusses revocation,
and [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2) describes stronger refresh-token replay defenses
that require additional controls. This login API is not an OAuth authorization server.

A copied refresh JWT plus its CSRF value can renew both credentials before that refresh expires, then continue using
the replacements until the original absolute deadline. Neither renewed JWT can outlive that deadline. Replacement
alone does not provide single-use enforcement. Rotating the signing key invalidates all JWTs. With the same signing
key and configuration, JWTs remain valid across restarts and instances without a shared session store. Question
storage and rate limiting still support only one backend process; stateless authentication alone does not enable replicas.

The previous `BrowserSessions` registry of credential digests, CSRF values and timestamps has been removed. The
remaining rate limiter uses connection IPs as transient process-local keys. Routine request logs contain method,
path and status, excluding query strings, bodies, credential headers and IP addresses. Unexpected exception causes
are logged separately, so internal logs still need access controls and retention review. Hosting and reverse-proxy
logs also need their own review.

### CSRF, CORS and XSS

Cookies are attached automatically by browsers. HttpOnly prevents scripts from directly reading their values; it does not
prevent scripts from sending authenticated requests. Cookie-authenticated changes therefore require exactly one matching CSRF
header and an exact trusted Origin. SameSite provides another layer.
[OWASP's CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
explains the separate roles of these controls.

`ADMIN_CORS_ALLOWED_ORIGINS` accepts explicit HTTP(S) origins and rejects wildcards. Browser login, cookie-authenticated
changes, refresh and logout require an allowed Origin. Metadata GET may omit Origin for same-origin requests;
an explicit untrusted Origin is rejected. A custom Ktor plugin separates public CORS, which may allow `*` without
credentials, from credentialed access for explicit admin origins. It also varies responses by Origin. CORS does not
grant admin permission. An explicit Authorization header takes precedence; invalid bearer credentials cannot fall
back to a valid cookie. Bearer-authenticated question changes do not use the cookie CSRF check.

Browser session routes and responses issued by bearer login use `Cache-Control: no-store`; successful answer
responses also use it. Requests carrying an Authorization header or access cookie are marked no-store by the HTTP
plugin. This reduces caching of credentials and sensitive results without claiming every rejected login request
has identical cache headers.

Prefer the frontend's same-origin `/api` proxy and keep Secure enabled in production. Local HTTP development explicitly
sets `AUTH_COOKIE_SECURE=false`. Direct cross-site cookies need SameSite=None and Secure, and browser policy can still
block them. See [MDN's cookie documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).
Settings reject SameSite=None with Secure disabled. The backend tests cannot establish the deployed browser's policy.

HttpOnly does not eliminate XSS: injected JavaScript could read CSRF metadata and perform authenticated operations.
The frontend must render user data as text, avoid unsafe HTML injection, and encode for any actual HTML/JS context.
JSON serialization alone does not prove safe frontend rendering. MFA/passkeys and operational hardening remain separate work.

## Input, storage and abuse controls

The JSON decoder rejects unknown keys, lenient syntax and value coercion. Question text is limited to 1–500 trimmed
characters; options must contain 2–10 choices of 1–200 trimmed characters each. Options must be unique after trimming
and case normalization, and the correct answer must match one option under the same normalization. Submitted
answers have a 1–200-character trimmed limit. UUID route IDs are validated before repository access.

PATCH validates supplied fields, then the repository validates the merged question before saving. For example,
changing only the options cannot leave an old correct answer outside those options. Generated models describe the
message shape; these runtime checks protect its meaning. Field limits apply after JSON decoding and do not provide
an overall request-body size limit.

The external JSON file contains correct answers and must stay private. Missing or blank storage is seeded; invalid
nonblank content fails startup without being overwritten. Mutations save the replacement file before publishing
the updated in-memory snapshot. Temporary-file replacement reduces partial writes, with atomic movement where
supported, but backups and recovery still need separate procedures. There are no SQL queries in this implementation;
SQL storage would need parameterized queries. A valid UUID alone grants no permission to edit a question.

| Limit | Current scope | Why and limitation |
|---|---|---|
| 5 login attempts per minute | Shared by bearer and browser login per connection IP. | Limits repeated password checks, including bcrypt's CPU cost. |
| 30 refreshes per minute | Separate refresh counter per connection IP. | Bounds repeated token issuance without sharing the answer allowance. |
| 30 answers per minute | Shared across question IDs per connection IP. | Slows automated answer guessing; it cannot prevent guessing entirely. |

These counters reset on restart and do not coordinate replicas. Shared school networks may share one allowance.
No trusted forwarded-IP handling is configured; behind a reverse proxy, connection IPs may identify the proxy
rather than individual players. A shared limiter and a trusted proxy configuration would need deliberate setup.

## First threat model

The boundaries are client-to-API traffic and the API's access to files and secrets. This first [STRIDE](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats) review covers both.

| Threat                 | Example in this project                | Control or remaining work                                                                                    |
|------------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Spoofing               | Forged JWT or reused stolen credential. | Verify signatures, claims, purpose and expiry; stolen JWTs retain the stateless replay limits. HTTPS is required. |
| Tampering              | Unauthorised question changes or CSRF. | Protect mutations, check Origin and CSRF for cookies, and validate input. Deployment needs file permissions. |
| Repudiation            | An admin denies a deletion.            | Request logs exist; an admin audit trail is missing.                                                         |
| Information disclosure | Errors expose internal details.        | Generic client errors and dotenv redaction. Review internal exception logs.                                  |
| Denial of service      | Requests exhaust resources.            | Limits are process-local. Body-size limits and load testing remain.                                          |
| Elevation of privilege | A public request or refresh JWT attempts an admin write. | Require an access JWT for the configured subject and admin role. |

## Validation and its limits

| Evidence | What it establishes |
|---|---|
| [PasswordHashTest.kt](../../../src/test/kotlin/PasswordHashTest.kt), [AuthServiceTest.kt](../../../src/test/kotlin/AuthServiceTest.kt) | Random salts, work factors, exact credentials, Unicode behavior, wrong-key rejection and no silent 72-byte truncation. |
| [ApiSettingsTest.kt](../../../src/test/kotlin/ApiSettingsTest.kt), [LocalEnvironmentTest.kt](../../../src/test/kotlin/LocalEnvironmentTest.kt) | Required hashes/secrets, ordered lifetimes, cookie/origin settings, configuration precedence, bcrypt quoting and secret-safe parsing failures. |
| [JwtTokensTest.kt](../../../src/test/kotlin/JwtTokensTest.kt) | Signature and purpose rejection, malformed claims, exact expiry, metadata and 144 renewals still bounded by the original 24-hour deadline. |
| [BrowserTokenExpiryTest.kt](../../../src/test/kotlin/BrowserTokenExpiryTest.kt) | One issuance-clock snapshot per pair, expiry during response preparation and expired refresh handling that clears cookies. |
| [BrowserSessionTest.kt](../../../src/test/kotlin/BrowserSessionTest.kt), [StatelessAuthTest.kt](../../../src/test/kotlin/StatelessAuthTest.kt) | Cookie scope, authenticated changes, Origin/CSRF rejection, metadata, independent-instance verification and logout without copied-token revocation. |
| [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt), [ContractTest.kt](../../../src/test/kotlin/ContractTest.kt) | HTTP rejection cases, rate limits, CORS, safe errors, public answer omission and actual responses checked against OpenAPI. |
| [RequestValidationTest.kt](../../../src/test/kotlin/RequestValidationTest.kt), [QuestionRepositoryTest.kt](../../../src/test/kotlin/QuestionRepositoryTest.kt) | Validation boundaries, merged question consistency, malformed storage and persistence failures. |

These tests use isolated test credentials, temporary files and controlled clocks. They run the backend in process
and do not establish browser UI or deployed HTTPS/proxy behavior.

The [testing document](../starting-points/testing-fundamentals.md) records a separate local real-backend browser smoke
run on 10 November 2026: Chromium used the Vue frontend and Vite proxy against the packaged Ktor JAR with 2-second
access, 6-second refresh and 18-second absolute lifetimes. Five protected creates passed across repeated renewal;
both JWT cookies changed while the original login claims stayed fixed. Missing CSRF returned `403`, the final
refresh after the absolute deadline returned `401`, the UI required a fresh login, and logout cleared both cookies.
This was an ad hoc local run, rather than a checked-in CI fixture. The frontend Playwright suite uses its own fake
API and provides separate evidence.

To run backend contract validation, tests and coverage gates in **PowerShell 7+**:

```powershell
./gradlew.bat check
```

In **Linux/Bash**:

```bash
./gradlew check
```

`check` enforces 90% line and 80% branch coverage for handwritten application code; generated DTOs are excluded.
The [CI workflow](../../../.github/workflows/ci.yml) runs validation, these checks and JAR packaging with read-only
repository permissions and no persisted checkout credentials. Docker builds run the same checks before packaging.
CI currently has no automated npm, JVM or image vulnerability scan and does not deploy the application. Tests and
coverage establish specific behavior, not complete security or completed vulnerability labs.

## Privacy, regulations and impact

I use the [GDPR text](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng) and the European Commission's
[explanation of its principles](https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/principles-gdpr_en)
as references for data minimisation, protection by design and appropriate security. The table connects those
principles to technical measures; it does not establish the project's legal scope or compliance.

| Principle                                        | Measure in this API                                                                     | What remains                                                                                 |
|--------------------------------------------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| GDPR: data minimisation and protection by design | No player registration, stored player profiles, answer history or authentication-session registry.                       | Check hosting logs, retention and the processing purpose before deployment.                  |
| GDPR: appropriate security                       | Restricted admin operations, token verification and bounded input.                      | Assess deployment risks, HTTPS, access to logs and operational procedures.                   |

The admin username appears in JWTs and browser metadata. Connection IPs used for rate limiting and hosting logs
can still involve personal data. Avoiding player accounts and a session registry reduces retained data; it does
not remove the need to review processing purposes, log access and retention. Non-root container execution limits
runtime privileges, while private file permissions, HTTPS, secrets and backups remain deployment responsibilities.

## Dependency scan triage

On 10 November 2026, `npm --prefix shared audit --json` reported seven affected dependency entries: five high and
two moderate, all in the development-only OpenAPI Generator wrapper tree. The check used the pinned shared revision
`a7fbb56`; three underlying advisories concern basic-ftp
([CPU denial of service](https://github.com/advisories/GHSA-c475-qrg2-pj4r)), glob
([cancellation bypass](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4)) and minimatch
([denial of service](https://github.com/advisories/GHSA-g3hv-75wg-m249)); the other entries propagate those findings.
These npm packages belong to the shared TypeScript-contract tooling. The backend uses the Gradle/Java generator;
its JAR and container image do not include this npm dependency tree. Processing the repository's trusted OpenAPI
file limits exposure without fixing the vulnerabilities. Findings remain open; untrusted specs or proxy
configuration should not be processed with this toolchain. npm suggests a breaking downgrade of the wrapper to
`2.13.12`, which needs separate compatibility review. No dependency changes were applied.

The audit command works in **PowerShell 7+ and Linux/Bash**, from the backend root:

```sh
npm --prefix shared audit --json
```

It currently exits with status `1` because vulnerabilities were reported. This is a dated scan of the shared npm
tooling; it does not establish that the JVM dependencies or container image are free of vulnerabilities.

This was an npm audit, not an OWASP Dependency-Check or Snyk run. The JVM runtime dependencies, a peer code-review
report, a CRA applicability assessment and completed vulnerability labs still require their own evidence. The GDPR
measures above describe technical choices, not a claim of regulatory compliance.
