# Authentication & Authorization Basics
- **Knowledge article url:** https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/authentication-and-authorization-basics/
- **Author:** Remzi Çavdar
- **AI disclosure:** I used OpenAI Codex to assist in drafting this document by analysing my codebase, identifying
  relevant implementations, and comparing my work against the AUAS BoKSA knowledge article. Codex helped structure and
  formulate the documentation based on these findings. I independently reviewed, verified, and edited the generated
  content to ensure it accurately reflects my work and understanding. I take full responsibility for the accuracy,
  completeness, and quality of this document.

Authentication checks who the admin is; authorization controls which actions they may perform. My quiz allows anonymous play and has one configured admin. This provides partial coverage of this article.

| Topic                   | Current implementation and gaps                                                                                                                                                                                                                                | Code evidence                                                                                                                |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| Authentication flow     | API clients use `POST /api/v1/auth/login`. Browsers use HttpOnly JWT cookies through `/api/v1/auth/session`, refresh and logout. Registration is not implemented. | [AuthRouting.kt](../../../src/main/kotlin/AuthRouting.kt), [BrowserTokens.kt](../../../src/main/kotlin/BrowserTokens.kt) |
| JWT authentication      | Both browser cookies and bearer credentials are signed JWTs. Verification checks signature, issuer, audience, subject, admin role, token purpose, issue time, expiry and the signed original sign-in deadline. Access defaults to five minutes, refresh to ten minutes from issuance, and the absolute login lifetime to 24 hours. No backend session registry exists. | [JwtTokens.kt](../../../src/main/kotlin/JwtTokens.kt), [Security.kt](../../../src/main/kotlin/Security.kt) |
| Password storage        | `ADMIN_PASSWORD_HASH` stores a salted bcrypt hash. Login verifies the supplied password; the backend does not require the plaintext password in configuration. The hash-generation tool uses cost 12 and a fresh random salt. | [PasswordHash.kt](../../../src/main/kotlin/PasswordHash.kt), [ApiSettings.kt](../../../src/main/kotlin/ApiSettings.kt) |
| Roles and authorization | Management routes require `admin` and the configured subject. Players are anonymous; only one role is defined, so the two-role criterion is not met.                                                                                                           | [Security.kt](../../../src/main/kotlin/Security.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt)                       |
| Security pitfalls       | Cookie writes require exact Origin and JWT-bound CSRF checks. Refresh renews both JWTs within the original absolute deadline and preserves CSRF. Logout clears cookies; copied JWTs remain valid until expiry. Stateless validation cannot detect refresh reuse or revoke one token. | [BrowserTokens.kt](../../../src/main/kotlin/BrowserTokens.kt), [AuthRouting.kt](../../../src/main/kotlin/AuthRouting.kt) |

Salted, slow hashing avoids storing the original password and makes offline guessing more expensive. Unique salts limit
reuse of precomputed hashes. Bcrypt has a 72-byte input limit, so the generator and login enforce UTF-8 byte length
instead of silently truncating a password. See [OWASP's password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

`BrowserSessions` previously kept credential digests, CSRF values and deadlines in memory, without storing IPs. It has
been removed. The remaining IP use is process-local rate limiting, separate from authentication. Tokens survive a
restart when the signing key and configuration remain the same. Their claims contain no password or client IP.

I configure access, refresh and absolute lifetimes through `JWT_TOKEN_TTL_SECONDS`, `JWT_REFRESH_TOKEN_TTL_SECONDS`
and `JWT_ABSOLUTE_TTL_SECONDS` (defaults 300, 600 and 86400). All are positive integer seconds, with access ≤ refresh
≤ absolute. Both JWTs preserve signed `auth_time` and `absolute_exp` across renewal. New tokens are shortened near
the absolute deadline. If the current refresh expires before renewal, I must sign in again even before 24 hours.
Earlier tokens missing those claims also require a fresh sign-in.

[ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers login, bearer protection and rejection of invalid, expired, forged and non-admin tokens. Run `./gradlew test` from the backend root (PowerShell 7+ and Bash).

Related deepening: [Secure coding practices](../deepening/secure-coding-practices.md).
