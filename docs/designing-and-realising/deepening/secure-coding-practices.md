# Secure Coding Practices
- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/secure-coding-practices/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to assist in drafting this document by analysing my codebase, identifying
  relevant implementations, and comparing my work against the AUAS BoKSA knowledge article. Codex helped structure and
  formulate the documentation based on these findings. I independently reviewed, verified, and edited the generated
  content to ensure it accurately reflects my work and understanding. I take full responsibility for the accuracy,
  completeness, and quality of this document.

## My goal and choices

I use this project to practise securing a Ktor API. Players can answer without an account; only an admin can change content. 
I want to protect credentials, question data and availability.

Separate public and admin routes avoid collecting player profiles. The Vue admin interface uses an opaque session in an HttpOnly cookie. 
The existing bearer JWT flow remains available to API clients. Browser sessions can be revoked individually; 
bearer JWTs have no per-token revocation and remain usable until expiry. 
Rotating the signing key invalidates all bearer tokens, including legitimate ones.

## Security in my development process

The [OWASP guidance](https://devguide.owasp.org/en/02-foundations/02-secure-development/) places security throughout the development lifecycle. 
I am using it to make checks more systematic.

For the next changes, my Definition of Done is:

- Identify affected data, trust boundaries and possible misuse.
- Implement controls and test allowed and rejected requests.
- Review the change using the [OWASP secure code review checklist](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html).
- Check dependency findings and record remaining risks before release.

Peer review and dependency scanning are still pending.

## What the implementation shows

| Concern                 | Current measure                                                                                                                             | Code evidence                                                                                                                                                             |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Unauthorised changes    | Mutations require a verified admin JWT or a live browser session. Cookie writes also require a trusted Origin and session-bound CSRF token. | [Security.kt](../../../src/main/kotlin/Security.kt), [BrowserSessions.kt](../../../src/main/kotlin/BrowserSessions.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt) |
| Invalid input           | Typed JSON, length limits, unique options and validation after merging a PATCH. Unknown fields are rejected.                                | [RequestValidation.kt](../../../src/main/kotlin/RequestValidation.kt), [Serialization.kt](../../../src/main/kotlin/Serialization.kt)                                      |
| Secret exposure         | Configuration holds secrets. Git ignores `../../../.env`; dotenv errors omit secret-bearing lines.                                          | [LocalEnvironment.kt](../../../src/main/kotlin/LocalEnvironment.kt), [.gitignore](../../../.gitignore)                                                                    |
| Excessive public data   | Public questions omit answers; checking happens on the server.                                                                              | [Models.kt](../../../src/main/kotlin/Models.kt)                                                                                                                           |
| Abuse and error leakage | Per connection IP: 5 logins/minute and 30 answers/minute. Client errors omit internal exception details.                                    | [RateLimiting.kt](../../../src/main/kotlin/RateLimiting.kt), [StatusPages.kt](../../../src/main/kotlin/StatusPages.kt)                                                    |

CORS controls browser origins, not admin permission. Knowing a question UUID also grants no permission to edit it.

## HttpOnly browser sessions

I moved browser authentication out of JavaScript-accessible storage. The frontend no longer stores an admin JWT in `sessionStorage` or `localStorage`, and removes the previous stored-session key during startup. Login returns metadata and a CSRF token; it never returns the cookie credential in JSON. This follows the cookie and session-storage considerations in [OWASP's session management guidance](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

The browser flow is separate from `POST /api/v1/auth/login`, so existing bearer API clients keep working:

1. `POST /api/v1/auth/session` checks the configured credentials, a trusted `Origin`, and the shared login rate limit. It issues a fresh random session ID and revokes the browser's previous session if present.
2. The server sets two host-only cookies with `HttpOnly`, `Secure`, and `SameSite=Strict`: `currency_quiz_admin` on `/api/v1` for access, and `currency_quiz_admin_refresh` on `/api/v1/auth/session` for renewal and logout. The refresh cookie is not sent to question routes. The production defaults require HTTPS; local HTTP development explicitly sets `AUTH_COOKIE_SECURE=false`.
3. The frontend uses `credentials: 'include'`. After a reload, `GET /api/v1/auth/session` returns `{ username, expiresIn, accessExpiresIn, absoluteExpiresIn, csrfToken }`. Metadata and the CSRF token stay in memory. A valid refresh cookie can restore metadata after access expires, but cannot itself authorise a question change.
4. `POST /api/v1/auth/session/refresh` rotates both credentials. The previous access credential stops working immediately. Replaying a spent refresh credential revokes its whole session family. Refresh does not update either the idle or absolute deadline. A refresh is never automatically retried after a timeout.
5. Question changes, refresh, explicit activity, and `DELETE /api/v1/auth/session` require their cookie, an exact trusted `Origin`, and the matching `X-CSRF-Token` header. Logout revokes the family and clears both cookies using their original scopes. A failed request is not reported as a successful logout.

`BrowserSessions` generates both credentials and the independent CSRF token with `SecureRandom`, using 32 random bytes for each. Cookie credentials are stored only as SHA-256 digests. The store removes expired entries and holds at most 1,000 families, evicting the oldest when full. A family retains at most 128 spent refresh digests for reuse detection; reaching that bound requires a fresh login rather than discarding replay protection. Rotation and registry changes are synchronized. This uses the rotation/reuse-detection principle from [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2), without claiming to implement OAuth.

### Five-minute access, fifteen-minute inactivity, thirty-minute session

| Deadline                | Default and maximum                                                  | What extends it                                                                    |
|-------------------------|----------------------------------------------------------------------|------------------------------------------------------------------------------------|
| Access credential       | `JWT_TOKEN_TTL_SECONDS=300` (5 minutes), also applied to bearer JWTs | A successful refresh issues a new access credential, within the session deadlines. |
| Browser inactivity      | `ADMIN_SESSION_IDLE_SECONDS=900` (15 minutes)                        | A CSRF-protected admin change or explicit user-activity request.                   |
| Overall browser session | `ADMIN_SESSION_ABSOLUTE_SECONDS=1800` (30 minutes from login)        | Nothing; credentials must be entered again.                                        |

The configuration can shorten these limits but cannot increase them. Access cannot exceed idle lifetime, and idle cannot exceed the overall lifetime. Old bearer JWTs with a lifetime over five minutes are rejected as well. A refresh credential is usable only while its family is within both server deadlines. Its cookie can remain until the absolute deadline; cookie presence is not proof of a valid session.

The frontend reports trusted pointer, keyboard, input, and scroll events while the visible admin workspace is in use, at most once per 30 seconds. `POST /api/v1/auth/session/activity` requires a live access cookie and CSRF protection. An open tab, a read-only session check, and refresh alone do not reset inactivity. There is no background renewal timer. Before a user-requested operation, the frontend renews access when needed, then sends the operation once. Browser Web Locks serialize refresh, login, and logout across tabs; a waiting tab checks the latest cookies before rotating. This prevents a late refresh response from restoring cookies after logout.

The backend enforces the deadlines using its own clock; browser timers only update the interface. The server verifies authenticated activity requests, but cannot independently prove a human pressed a key. An attacker already controlling a live session could report activity until the absolute deadline. These are explicit session controls for this prototype, not a claim of banking certification or equivalence to Dutch government authentication. MFA/passkeys, stronger password storage, independent penetration testing, audit logging, and operational controls remain separate work.

### CSRF, CORS and the deployment boundary

Cookies are sent automatically by the browser. `HttpOnly` stops JavaScript from reading the session cookie, but it does not prevent a page from sending authenticated requests. I therefore use a synchronizer CSRF token, compared in constant time, plus an exact Origin allowlist. `SameSite` is an additional control. [OWASP's CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) explains these separate roles.

`ADMIN_CORS_ALLOWED_ORIGINS` accepts explicit HTTP(S) origins and rejects `*`. Browser login and writes require an Origin from that list. A same-origin GET may omit Origin; an explicit untrusted Origin is rejected. Browser-session responses, including errors, use `Cache-Control: no-store`.

Public API CORS remains controlled by `CORS_ALLOWED_ORIGINS`, which may be `*`. The HTTP plugin enables credentialed CORS only for exact admin origins and varies responses by Origin. Public wildcard responses do not enable credentials. Bearer credentials take precedence when an Authorization header is supplied; an invalid bearer header cannot silently fall back to a valid cookie.

The Netlify frontend proxies `/api/*` to `https://currencyquiz-backend.onrender.com/api/*`. This makes the session cookies first-party for the frontend host and supports `SameSite=Strict`. Render must allow the actual frontend origin, even through that proxy. The frontend's `VITE_API_BASE_URL` stays empty. Direct cross-site cookie authentication would require `AUTH_COOKIE_SAME_SITE=None` with `Secure=true`, and may still be blocked by browser third-party-cookie policies. [MDN's cookie documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie) describes these attributes; the same-origin proxy avoids depending on cross-site cookies.

### Remaining limits

The session store belongs to one backend process. Render restarts, deploys, or free-plan spin-downs clear browser sessions, so an administrator must sign in again. Multiple backend instances would need a shared store with atomic rotation and revocation; persistent login is not implemented. Cookies are shared between tabs for the same host, so revoking a session affects those tabs on their next server check.

HttpOnly is not complete XSS protection: injected JavaScript could still read the CSRF response and perform requests while the page is open. The frontend must continue rendering question data as text, avoid unsafe HTML, and protect its dependencies. A stolen cookie can be replayed until expiry or revocation. HTTPS, host security, and keeping credentials out of logs remain necessary.

## First threat model

The boundaries are client-to-API traffic and the API's access to files and secrets. This first [STRIDE](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats) review covers both.

| Threat                 | Example in this project                | Control or remaining work                                                                                    |
|------------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Spoofing               | Forged admin token or session.         | Verify JWT claims or look up an unexpired opaque session. HTTPS is required.                                 |
| Tampering              | Unauthorised question changes or CSRF. | Protect mutations, check Origin and CSRF for cookies, and validate input. Deployment needs file permissions. |
| Repudiation            | An admin denies a deletion.            | Request logs exist; an admin audit trail is missing.                                                         |
| Information disclosure | Errors expose internal details.        | Generic client errors and dotenv redaction. Review internal exception logs.                                  |
| Denial of service      | Requests exhaust resources.            | Limits are process-local. Body-size limits and load testing remain.                                          |
| Elevation of privilege | Another role gains admin access.       | Check the configured subject and admin role.                                                                 |

## Validation and its limits

[ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers rejected admin requests, invalid tokens and input, rate limits, CORS and errors. [LocalEnvironmentTest.kt](../../../src/test/kotlin/LocalEnvironmentTest.kt) checks precedence and secret-safe parsing errors.

[BrowserSessionTest.kt](../../../src/test/kotlin/BrowserSessionTest.kt) exercises cookie flags and scope, authenticated changes, session restoration, CSRF and Origin rejection, expiry, rotation, revocation, the bounded registry, and public versus credentialed CORS. [ApiSettingsTest.kt](../../../src/test/kotlin/ApiSettingsTest.kt) checks the explicit allowlist and rejects `SameSite=None` without Secure. Frontend client/session tests cover credentialed requests, in-memory state, legacy storage cleanup and failed logout handling.

I can rerun these checks with:

```sh
./gradlew test
```

Regression tests demonstrate specific controls, not complete security or completed vulnerability labs.

## Privacy, regulations and impact

I connect the requirements to concrete measures while keeping the limits visible and used GDPR law as a source: https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng

| Requirement                                      | Measure in this API                                                                     | What remains                                                                                 |
|--------------------------------------------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| GDPR: data minimisation and protection by design | No player registration, stored player profiles or answer history.                       | Check hosting logs, retention and the processing purpose before deployment.                  |
| GDPR: appropriate security                       | Restricted admin operations, token verification and bounded input.                      | Assess deployment risks, HTTPS, access to logs and operational procedures.                   |

Connection IPs used for rate limiting can still be personal data. Shared school networks may group students under one limit.
