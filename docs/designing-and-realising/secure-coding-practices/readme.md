# Secure coding practices

**Project:** Currency auiz API

## My goal and choices

I use this project to practise securing a Ktor API. Players can answer without an account; only an admin can change content. I want to protect credentials, question data and availability.

Separate public and admin routes avoid collecting player profiles. JWTs allow verification without a session store. There is no per-token revocation: a stolen token can work until expiry. Rotating the signing key and restarting invalidates all tokens, including legitimate ones.

## Security in my development process

The [OWASP guidance](https://devguide.owasp.org/en/02-foundations/02-secure-development/) places security throughout the development lifecycle. I am using it to make checks more systematic.

For the next changes, my Definition of Done is:

- Identify affected data, trust boundaries and possible misuse.
- Implement controls and test allowed and rejected requests.
- Review the change using the [OWASP secure code review checklist](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html).
- Check dependency findings and record remaining risks before release.

Peer review and dependency scanning are still pending.

## What the implementation shows

| Concern                 | Current measure                                                                                              | Code evidence                                                                                                                        |
|-------------------------|--------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| Unauthorised changes    | Mutations require an admin JWT. Signature, issuer, audience, expiry, subject and role are checked.           | [Security.kt](../../../src/main/kotlin/Security.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt)                               |
| Invalid input           | Typed JSON, length limits, unique options and validation after merging a PATCH. Unknown fields are rejected. | [RequestValidation.kt](../../../src/main/kotlin/RequestValidation.kt), [Serialization.kt](../../../src/main/kotlin/Serialization.kt) |
| Secret exposure         | Configuration holds secrets. Git ignores `.env`; dotenv errors omit secret-bearing lines.                    | [LocalEnvironment.kt](../../../src/main/kotlin/LocalEnvironment.kt), [.gitignore](../../../.gitignore)                               |
| Excessive public data   | Public questions omit answers; checking happens on the server.                                               | [Models.kt](../../../src/main/kotlin/Models.kt)                                                                                      |
| Abuse and error leakage | Per connection IP: 5 logins/minute and 30 answers/minute. Client errors omit internal exception details.     | [RateLimiting.kt](../../../src/main/kotlin/RateLimiting.kt), [StatusPages.kt](../../../src/main/kotlin/StatusPages.kt)               |

CORS controls browser origins, not admin permission. Knowing a question UUID also grants no permission to edit it.

## First threat model

The boundaries are client-to-API traffic and the API's access to files and secrets. This first [STRIDE](https://learn.microsoft.com/en-us/azure/security/develop/threat-modeling-tool-threats) review covers both.

| Threat                 | Example in this project          | Control or remaining work                                                   |
|------------------------|----------------------------------|-----------------------------------------------------------------------------|
| Spoofing               | Forged admin token.              | Verify signature and claims. Deployment still needs HTTPS.                  |
| Tampering              | Unauthorised question changes.   | Protect mutations and validate input. Deployment needs file permissions.    |
| Repudiation            | An admin denies a deletion.      | Request logs exist; an admin audit trail is missing.                        |
| Information disclosure | Errors expose internal details.  | Generic client errors and dotenv redaction. Review internal exception logs. |
| Denial of service      | Requests exhaust resources.      | Limits are process-local. Body-size limits and load testing remain.         |
| Elevation of privilege | Another role gains admin access. | Check the configured subject and admin role.                                |

## Validation and its limits

[ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers rejected admin requests, invalid tokens and input, rate limits, CORS and errors. [LocalEnvironmentTest.kt](../../../src/test/kotlin/LocalEnvironmentTest.kt) checks precedence and secret-safe parsing errors.

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

Connection IPs used for rate limiting can still be personal data. Shared school networks may group students under one limit. I need feedback from a player and content administrator on that trade-off; no conversations are recorded yet.