# Authentication & Authorization Basics
- Knowledge article url: https://boksa.hbo-ict-hva.nl/profiles/full-stack-developer/design-and-realise/authentication-and-authorization-basics/
- Author: Remzi Çavdar
- AI disclosure: ChatGPT helped me draft this article. I reviewed and edited the content to my satisfaction, and take
  full responsibility for the accuracy, completeness and usefulness of the content.

Authentication checks who the admin is; authorization controls which actions they may perform. My quiz allows anonymous play and has one configured admin. This provides partial coverage of this article.

| Topic                   | Current implementation and gaps                                                                                                                                                                                                                                | Code evidence                                                                                                                |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| Authentication flow     | API clients use `POST /api/v1/auth/login`. The Vue admin interface uses HttpOnly access/refresh cookies through `/api/v1/auth/session`, including refresh, activity and logout. Registration is not implemented.                                               | [Routing.kt](../../../src/main/kotlin/Routing.kt), [BrowserSessions.kt](../../../src/main/kotlin/BrowserSessions.kt)         |
| JWT authentication      | Bearer signatures, issuer, audience, subject, role, issue time and expiry are verified; lifetime is capped at five minutes. Browser cookies are separate opaque credentials, checked against the server registry.                                              | [Security.kt](../../../src/main/kotlin/Security.kt), [BrowserSessionTest.kt](../../../src/test/kotlin/BrowserSessionTest.kt) |
| Password storage        | Configuration contains the plaintext admin password. Unsalted SHA-256 hashes are used for comparisons in memory; bcrypt/Argon2 password storage is missing.                                                                                                    | [ApiSettings.kt](../../../src/main/kotlin/ApiSettings.kt), [Security.kt](../../../src/main/kotlin/Security.kt)               |
| Roles and authorization | Management routes require `admin` and the configured subject. Players are anonymous; only one role is defined, so the two-role criterion is not met.                                                                                                           | [Security.kt](../../../src/main/kotlin/Security.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt)                       |
| Security pitfalls       | Server-side access checks, Origin/CSRF checks for cookie writes, five-minute access, fifteen-minute inactivity and thirty-minute absolute session limits. Refresh reuse and logout revoke the browser family. Bearer JWTs still have no individual revocation. | [Routing.kt](../../../src/main/kotlin/Routing.kt), [BrowserSessions.kt](../../../src/main/kotlin/BrowserSessions.kt)         |

Salted, slow hashing (such as Argon2id) avoids storing the original password and makes offline guessing more expensive. Unique salts limit reuse of precomputed hashes. See [OWASP's password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html); this remains an implementation gap here.

[ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers login, bearer protection and rejection of invalid, expired, forged and non-admin tokens. Run `./gradlew test` from the backend root (PowerShell 7+ and Bash).

Related deepening: [Secure coding practices](../deepening/secure-coding-practices.md).
