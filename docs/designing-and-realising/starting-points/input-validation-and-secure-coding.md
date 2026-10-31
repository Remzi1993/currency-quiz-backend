# Input Validation & Secure Coding

I validate requests in the backend before changing question data. The frontend handles safe browser rendering. SQL parameter binding is not demonstrated because this project stores questions in a JSON file.

| Topic | Current implementation and limits | Code evidence |
|-------|-----------------------------------|---------------|
| Server-side validation | Login fields must be nonblank and bounded. Questions require text of 1–500 trimmed characters, 2–10 unique options and a correct answer matching an option. IDs must match UUID format. There is no registration endpoint. | [RequestValidation.kt](../../../src/main/kotlin/RequestValidation.kt), [Routing.kt](../../../src/main/kotlin/Routing.kt), [QuestionRepository.kt](../../../src/main/kotlin/QuestionRepository.kt) |
| Allowed fields and types | Typed JSON decoding rejects malformed bodies, incorrect types and unknown fields. Validation failures return `400` with a consistent error body. These rules define accepted input rather than blocking a list of suspicious strings. | [Serialization.kt](../../../src/main/kotlin/Serialization.kt), [StatusPages.kt](../../../src/main/kotlin/StatusPages.kt) |
| Output handling | Responses use JSON serialization rather than building HTML from request values. Trimming text is normalization, not HTML sanitisation; browser rendering needs its own protection. | [Serialization.kt](../../../src/main/kotlin/Serialization.kt) |
| SQL injection | Storage uses a map and JSON file, with no SQL or ORM. The parameter-binding criterion is therefore missing evidence here. With SQL, bound values stay separate from query syntax, unlike concatenated input. | [QuestionRepository.kt](../../../src/main/kotlin/QuestionRepository.kt), [OWASP parameter binding](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html) |
| Small code review | In `update`, checking only supplied PATCH fields could leave the existing answer outside the new options. The secure pattern is to merge first, then call `validatedQuestion` before persisting; the current code does this. | [QuestionRepository.kt](../../../src/main/kotlin/QuestionRepository.kt), [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) |

The rules follow [OWASP's input validation guidance](https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html). [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) covers malformed input, unknown fields and rejected PATCH changes. Run `./gradlew test` from the backend root (PowerShell 7+ and Bash).

Deepening: [Secure coding practices](../deepening/secure-coding-practices.md).
