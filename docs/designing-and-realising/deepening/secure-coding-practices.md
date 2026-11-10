# Secure Coding Practices
- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/secure-coding-practices/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to assist in drafting this document by analysing my codebase, identifying
  relevant implementations, and comparing my work against the AUAS BoKSA knowledge article. Codex helped structure and
  formulate the documentation based on these findings. I independently reviewed, verified, and edited the generated
  content to ensure it accurately reflects my work and understanding. I take full responsibility for the accuracy,
  completeness, and quality of this document.

## My goal and choices

Players can answer questions without an account. Only the configured administrator may change content. I use JWTs
for authentication and verify the admin role for authorization. The backend records no authentication sessions,
token registry or browser activity. This reduces stored personal data and keeps the authentication code small.

Browsers keep access and refresh JWTs in HttpOnly cookies. API clients receive an access JWT in JSON. Both use the
same signature and claims validation. There is one administrator role; registration and a second role are not implemented.

## Security in the development process

The [OWASP guidance](https://devguide.owasp.org/en/02-foundations/02-secure-development/) places security throughout
the development lifecycle. For authentication changes, my Definition of Done is:

- Identify affected secrets, trust boundaries and possible misuse before implementation.
- Cover permitted requests and rejection cases, including token purpose, expiry, Origin and CSRF.
- Review the change against the [OWASP secure code review checklist](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html).
- Run contract validation, tests and coverage gates; record dependency findings and remaining risks.

A peer review, independent penetration test and completed OWASP vulnerability labs remain separate evidence gaps.

## What the implementation shows

| Concern | Current measure | Code evidence |
|---|---|---|
| Unauthorised changes | Verified access JWT with the configured subject and admin role. Refresh JWTs cannot authorize question changes. | [JwtTokens.kt](../../../src/main/kotlin/JwtTokens.kt), [Security.kt](../../../src/main/kotlin/Security.kt) |
| Password storage | Salted bcrypt hash in configuration; verification never needs a configured plaintext password. | [PasswordHash.kt](../../../src/main/kotlin/PasswordHash.kt), [ApiSettings.kt](../../../src/main/kotlin/ApiSettings.kt) |
| Cookie request forgery | Exact Origin allowlist and a random nonce bound to signed JWT claims, compared with the CSRF header in constant time. | [BrowserTokens.kt](../../../src/main/kotlin/BrowserTokens.kt) |
| Invalid input | Typed JSON, length limits, unique options and validation after merging PATCH fields. Unknown fields are rejected. | [RequestValidation.kt](../../../src/main/kotlin/RequestValidation.kt), [Serialization.kt](../../../src/main/kotlin/Serialization.kt) |
| Secret exposure | Configuration has no secret-bearing string representation. Dotenv errors omit secret-bearing lines. Credentials are absent from request logs. | [LocalEnvironment.kt](../../../src/main/kotlin/LocalEnvironment.kt), [Application.kt](../../../src/main/kotlin/Application.kt) |
| Excessive public data | Public questions omit correct answers; answer checking happens on the server. | [Models.kt](../../../src/main/kotlin/Models.kt) |
| Abuse and error leakage | Process-local connection-IP limits on login, refresh and answers. Generic client errors omit exception details. | [RateLimiting.kt](../../../src/main/kotlin/RateLimiting.kt), [StatusPages.kt](../../../src/main/kotlin/StatusPages.kt) |

Question content uses JSON-file storage, so this application has no SQL queries. Parameter binding must be used if
SQL storage is added. CORS does not grant admin permission; knowing a UUID does not permit editing it.

## Stateless JWTs in HttpOnly cookies

1. POST /api/v1/auth/session verifies JSON credentials against the salted bcrypt hash, a trusted Origin and the shared login rate limit.
2. It sets two host-only cookies with HttpOnly, Secure and SameSite=Strict defaults: currency_quiz_admin on /api/v1 and currency_quiz_admin_refresh on /api/v1/auth/session. The refresh cookie is not normally sent to question routes.
3. Browser requests use credentials: include. GET /api/v1/auth/session returns only username, expiresIn, accessExpiresIn and csrfToken. These values stay in memory; neither authentication JWT appears in JSON.
4. POST /api/v1/auth/session/refresh verifies the refresh JWT, Origin and CSRF. It replaces both JWT cookies, preserving the original sign-in deadline and CSRF nonce.
5. Question writes and logout require an access or applicable refresh JWT, trusted Origin and matching X-CSRF-Token. DELETE /api/v1/auth/session clears both original cookie scopes.

Access defaults to 300 seconds through JWT_TOKEN_TTL_SECONDS. Each refresh JWT defaults to 600 seconds through
JWT_REFRESH_TOKEN_TTL_SECONDS. JWT_ABSOLUTE_TTL_SECONDS sets the original sign-in limit, defaulting to 86400 seconds
(24 hours). All three accept positive integer seconds up to 2147483647, with access lifetime no longer than refresh
and refresh no longer than the absolute limit. Increasing these values deliberately increases the exposure window.

Both JWTs carry signed auth_time and absolute_exp claims. Login establishes these values once; renewal copies them
unchanged. Each new expiry is the earlier of its configured lifetime and the original absolute deadline. A refresh
with 20 seconds left therefore issues both JWTs with only 20 seconds remaining. A fresh sign-in is required at the
absolute deadline, or earlier if the current refresh JWT expires without renewal. There is no activity endpoint or
separate inactivity timer. Reading metadata issues no tokens and extends no expiry.

JWT verification fixes the signing algorithm and checks issuer, audience, configured subject, admin role, token
purpose, issue time, expiry and original login deadline. Login claims must be integers in the allowed order and
within the configured absolute lifetime; older JWTs without these claims require a fresh sign-in. Access and refresh
have separate purposes. The CSRF nonce is generated with
SecureRandom and bound to signed claims, so a header cannot substitute for an authentication JWT.

### Limits of stateless authentication

Logout removes browser cookies; it cannot invalidate a copied JWT. A refresh JWT can be reused until its own
expiry. There is no single-use refresh enforcement, replay detection or individual token revocation. Reissuing a JWT
would not invalidate its predecessor. These are explicit limits of removing server-side authentication state;
[OWASP's JWT guidance](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html) discusses revocation,
and [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2) describes stronger refresh-token replay defenses
that require additional controls. This login API is not an OAuth authorization server.

A copied refresh JWT plus its CSRF value can renew both credentials before that refresh expires, then continue using
the replacements until the original absolute deadline. Neither renewed JWT can outlive that deadline. Replacement
alone does not provide single-use enforcement. Rotating the signing key invalidates all JWTs. With the same signing key and configuration,
JWTs remain valid across restarts and instances without a shared session store.

BrowserSessions previously held credential digests, CSRF values and timestamps, without client IPs. That registry has
been removed. The remaining rate limiter uses connection IPs as transient process-local keys. Request logs contain
method, path and status, without IPs, passwords or JWTs. Hosting and reverse-proxy logs need their own retention review.

### CSRF, CORS and XSS

Cookies are attached automatically by browsers. HttpOnly prevents scripts from reading their values; it does not
prevent scripts from sending authenticated requests. Cookie writes therefore require exactly one matching CSRF
header and an exact trusted Origin. SameSite provides another layer.
[OWASP's CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
explains the separate roles of these controls.

ADMIN_CORS_ALLOWED_ORIGINS accepts explicit HTTP(S) origins and rejects wildcards. Browser login and writes require
an allowed Origin. Metadata GET may omit Origin for same-origin requests; an explicit untrusted Origin is rejected.
Authentication responses use Cache-Control: no-store. Public CORS may remain wildcard, without credentials.
An explicit Authorization header takes precedence; invalid bearer credentials cannot fall back to a valid cookie.

Prefer the frontend's same-origin /api proxy and keep Secure enabled in production. Local HTTP development explicitly
sets AUTH_COOKIE_SECURE=false. Direct cross-site cookies need SameSite=None and Secure, and browser policy can still
block them. See [MDN's cookie documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).

HttpOnly does not eliminate XSS: injected JavaScript could read CSRF metadata and perform authenticated operations.
The frontend must render user data as text, avoid unsafe HTML injection, and encode for any actual HTML/JS context.
JSON serialization alone does not prove safe frontend rendering. MFA/passkeys and operational hardening remain separate work.

## First threat model

The boundaries are client-to-API traffic and the API's access to files and secrets. This first [STRIDE](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats) review covers both.

| Threat                 | Example in this project                | Control or remaining work                                                                                    |
|------------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Spoofing               | Forged admin token or session.         | Verify JWT signatures, claims, purpose and expiry. HTTPS is required.                                 |
| Tampering              | Unauthorised question changes or CSRF. | Protect mutations, check Origin and CSRF for cookies, and validate input. Deployment needs file permissions. |
| Repudiation            | An admin denies a deletion.            | Request logs exist; an admin audit trail is missing.                                                         |
| Information disclosure | Errors expose internal details.        | Generic client errors and dotenv redaction. Review internal exception logs.                                  |
| Denial of service      | Requests exhaust resources.            | Limits are process-local. Body-size limits and load testing remain.                                          |
| Elevation of privilege | Another role gains admin access.       | Check the configured subject and admin role.                                                                 |

## Validation and its limits

[ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers rejected admin requests, invalid tokens and input, rate limits, CORS and errors. [LocalEnvironmentTest.kt](../../../src/test/kotlin/LocalEnvironmentTest.kt) checks precedence and secret-safe parsing errors.

[BrowserSessionTest.kt](../../../src/test/kotlin/BrowserSessionTest.kt) and [StatelessAuthTest.kt](../../../src/test/kotlin/StatelessAuthTest.kt) cover cookie flags and scope, authenticated changes, JWT metadata, CSRF/Origin rejection, expiry and independent-instance authentication. [ApiSettingsTest.kt](../../../src/test/kotlin/ApiSettingsTest.kt) checks lifetime configuration and cookie settings. These backend tests do not verify the browser UI or frontend migration.

[JwtTokensTest.kt](../../../src/test/kotlin/JwtTokensTest.kt) models 144 renewals with a controlled clock and checks
that the final tokens still expire at the original 24-hour deadline. It also checks malformed login claims and
renewal beyond the previous refresh expiry. [BrowserTokenExpiryTest.kt](../../../src/test/kotlin/BrowserTokenExpiryTest.kt)
checks one clock snapshot per issued pair and expiry during request handling. These checks do not need to wait a real day.

I can rerun these checks with:

```sh
./gradlew test
```

Regression tests demonstrate specific controls, not complete security or completed vulnerability labs.

## Privacy, regulations and impact

I connect the requirements to concrete measures while keeping the limits visible and used GDPR law as a source: https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng

| Requirement                                      | Measure in this API                                                                     | What remains                                                                                 |
|--------------------------------------------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| GDPR: data minimisation and protection by design | No player registration, stored player profiles, answer history or authentication-session registry.                       | Check hosting logs, retention and the processing purpose before deployment.                  |
| GDPR: appropriate security                       | Restricted admin operations, token verification and bounded input.                      | Assess deployment risks, HTTPS, access to logs and operational procedures.                   |

Connection IPs used for rate limiting can still be personal data. Shared school networks may group students under one limit.

## Dependency scan triage

On 9 November 2026, npm audit --json in shared reported seven dependency entries: five high and two moderate,
all in the development-only OpenAPI Generator wrapper tree. Three underlying advisories concern basic-ftp
([CPU denial of service](https://github.com/advisories/GHSA-c475-qrg2-pj4r)), glob
([cancellation bypass](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4)) and minimatch
([denial of service](https://github.com/advisories/GHSA-g3hv-75wg-m249)); the other entries propagate those findings.
These npm packages are not included in the backend JAR. The current workflow processes the repository's trusted
OpenAPI file, but that does not remove the vulnerabilities. Findings remain open; do not process untrusted specs or
proxy configuration with this toolchain. npm's suggested force fix downgrades the generator wrapper and needs separate
compatibility review. No force upgrade or downgrade was applied during this authentication change.

This was an npm audit, not an OWASP Dependency-Check or Snyk run. The JVM runtime dependencies, a peer code-review
report, a CRA applicability assessment and completed vulnerability labs still require their own evidence. The GDPR
measures above describe technical choices, not a claim of regulatory compliance.
