# Currency Quiz API

A Ktor REST API for playing a currency quiz without an account and managing questions with an admin JWT. Questions have a text prompt, answer options, and one correct answer. Public responses never include the correct answer; answers are checked on the server.

## Run locally

Use JDK 25 to build and run the API. The build uses a Java 25 toolchain and the included Gradle wrapper.

Create local configuration - example in PowerShell 7+:

```powershell
Copy-Item .env.example .env

# Generate a signing secret, then paste it into JWT_SECRET in .env.
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))

notepad .env
```

Set `ADMIN_PASSWORD` to a unique password of at least 12 characters and fill in `JWT_SECRET`. The template sets the username to `admin`; change it if desired. `CORS_ALLOWED_ORIGINS` can contain your frontend's origin.

After saving `.env`, start the server from the project root:

```sh
./gradlew run
```

The application loads `.env` automatically on each start. You do not need to set variables in the terminal again. In IntelliJ, select the shared **Currency Quiz API** run configuration. It runs `info.remzi.currencyquiz.MainKt` with the project root as its working directory. Launching `io.ktor.server.netty.EngineMain` directly bypasses the `.env` loader and can cause a `Missing required configuration: security.adminUsername` error.

The API listens on `http://localhost:8080`. Interactive Swagger documentation is available at `http://localhost:8080/openapi`.

The server requires credentials and a signing secret at startup; there are no default admin credentials. Keep the same JWT secret between restarts if existing tokens should remain valid. Changing it invalidates previously issued tokens.

| Variable                | Required | Default / constraints                                                                            |
|-------------------------|----------|--------------------------------------------------------------------------------------------------|
| `PORT`                  | No       | `8080`                                                                                           |
| `ADMIN_USERNAME`        | Yes      | Nonblank; at most 100 characters                                                                 |
| `ADMIN_PASSWORD`        | Yes      | 12–1024 characters; must not be blank                                                            |
| `JWT_SECRET`            | Yes      | At least 32 UTF-8 bytes; use a random secret                                                     |
| `JWT_ISSUER`            | No       | `currencyquiz-api`                                                                               |
| `JWT_AUDIENCE`          | No       | `currencyquiz-admin`                                                                             |
| `JWT_TOKEN_TTL_SECONDS` | No       | `3600`; from 1 to 86400 seconds                                                                  |
| `CORS_ALLOWED_ORIGINS`  | No       | Empty; comma-separated HTTP(S) origins, such as `http://localhost:5173,https://quiz.example.com` |
| `QUESTIONS_FILE`        | No       | `data/questions.json`, relative to the working directory                                         |

[.env.example](.env.example) lists the supported variables. `.env` is read from the process working directory and supports comments and double-quoted values. Existing OS environment variables and explicit JVM system properties take precedence over values in `.env`. A missing `.env` is allowed when settings come from your IDE or hosting environment; malformed entries cause a configuration error. `.env` is ignored by Git. Do not commit credentials or signing secrets.

## Endpoints

All request bodies use `Content-Type: application/json`.

| Method   | Path                             | Access            | Result                                                        |
|----------|----------------------------------|-------------------|---------------------------------------------------------------|
| `GET`    | `/api/v1/questions`              | Public            | `200`: question array without answers                         |
| `GET`    | `/api/v1/questions/{id}`         | Public            | `200`: one question without its answer                        |
| `POST`   | `/api/v1/questions/{id}/answers` | Public            | `200`: `{ "correct": true }` or `{ "correct": false }`        |
| `POST`   | `/api/v1/auth/login`             | Admin credentials | `200`: JWT access token                                       |
| `POST`   | `/api/v1/questions`              | Admin JWT         | `201`: created question; `Location` identifies its public URL |
| `PATCH`  | `/api/v1/questions/{id}`         | Admin JWT         | `200`: updated question                                       |
| `DELETE` | `/api/v1/questions/{id}`         | Admin JWT         | `204`: deleted, without a response body                       |

Management responses include `correctAnswer`. Send the token as `Authorization: Bearer <accessToken>` when creating, editing, or deleting a question. Tokens expire after the configured lifetime; log in again to obtain a new one.

### Example flow with PowerShell 7+

In another terminal, enter the same admin credentials saved in `.env`, then log in and create a question. The application loads `.env` for itself; it does not populate your terminal's environment.

```powershell
$baseUrl = "http://localhost:8080/api/v1"
$adminUsername = Read-Host "Admin username"
$adminPassword = Read-Host "Admin password" -MaskInput
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" `
    -ContentType "application/json" `
    -Body (@{ username = $adminUsername; password = $adminPassword } | ConvertTo-Json)

# Login returns { accessToken, tokenType: "Bearer", expiresIn: 3600 }.
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$question = Invoke-RestMethod -Method Post -Uri "$baseUrl/questions" `
    -Headers $headers -ContentType "application/json" `
    -Body (@{
        text = "Which currency is used in Japan?"
        options = @("USD", "EUR", "JPY", "GBP")
        correctAnswer = "JPY"
    } | ConvertTo-Json)

# Players do not need the Authorization header.
Invoke-RestMethod -Uri "$baseUrl/questions"
Invoke-RestMethod -Method Post -Uri "$baseUrl/questions/$($question.id)/answers" `
    -ContentType "application/json" -Body '{"answer":"JPY"}'

# PATCH accepts only the fields you want to change.
Invoke-RestMethod -Method Patch -Uri "$baseUrl/questions/$($question.id)" `
    -Headers $headers -ContentType "application/json" `
    -Body '{"text":"What is the currency of Japan?"}'

Invoke-RestMethod -Method Delete -Uri "$baseUrl/questions/$($question.id)" -Headers $headers
```

A question looks like this:

```json
{
  "id": "58d6497e-227a-4bc4-938e-159f449df87e",
  "text": "Which currency is used in Japan?",
  "options": ["USD", "EUR", "JPY", "GBP"]
}
```

### Validation and errors

Question text must contain 1–500 characters after trimming. Supply 2–10 options, each 1–200 characters after trimming, with no duplicates when compared without case. The correct answer must match one of those options. Submitted answers must contain 1–200 characters after trimming; comparison trims whitespace and ignores case.

PATCH requires at least one field. Omitted or `null` fields keep their previous values. The resulting question must satisfy the same rules as a new question: when replacing options, update `correctAnswer` in the same request if the old answer is no longer an option. IDs are UUIDs.

A valid but incorrect answer returns `200 OK` with `{"correct":false}`. Errors use a consistent JSON envelope:

```json
{
  "code": "validation_error",
  "message": "Request validation failed",
  "details": ["text must contain 1 to 500 characters"]
}
```

The `details` array is empty when there are no additional validation reasons. The status indicates the failure:

| Status | Meaning                                                                   |
|--------|---------------------------------------------------------------------------|
| `400`  | Invalid JSON, request fields, or question ID                              |
| `401`  | Invalid credentials or missing, expired, or invalid admin token           |
| `404`  | Question or route does not exist                                          |
| `415`  | Unsupported request content type                                          |
| `429`  | Rate limit exceeded; respect `Retry-After`                                |
| `500`  | Unexpected server error; internal details are logged and are not returned |

Login allows 5 requests per minute per client IP. Answer submission allows 30 requests per minute per client IP, shared across question IDs. These limits run in memory and reset when the server restarts.

## Storage and deployment

On startup, a missing or blank `QUESTIONS_FILE` is initialized from the bundled `questions-seed.json` resource. Parent directories are created automatically. A blank file includes a zero-byte file or one containing only whitespace. Existing question data is preserved, including an empty JSON array (`[]`), so deleting all questions does not restore the samples on restart. Invalid nonblank JSON causes startup to fail without replacing the file.

Questions are saved to `QUESTIONS_FILE` using atomic file replacement where the filesystem supports it. They survive restarts when the same file is retained. Use a persistent writable volume when deploying in a container. The file contains correct answers, so do not serve it as a public static asset or commit it.

`src/main/resources` is packaged into the application's JAR. It is suitable for configuration, static files, or read-only starter data. Mutable questions belong in the external `data/questions.json` file so updates can be written and retained across deployments.

[questions.seed.json](src/main/resources/questions-seed.json) contains the 40 sample questions with four choices each. The seed is validated before being copied into writable storage; subsequent admin changes update only the external file. The sample currency-country mappings were checked on 20 October 2026 against the [SIX ISO 4217 current currency list](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml), published 17 September 2026.

This repository is intended for one server process accessing the questions file. Use a database and shared rate limiting before running multiple instances. The local JSON repository is separate from routing so storage can be replaced later.

Expose the API over HTTPS in deployment and keep secrets in your hosting environment. CORS permits only the configured browser origins; it does not grant admin access. Rate limits use the connection's client IP; behind a reverse proxy, configure trusted proxy handling before relying on per-player limits. Never trust arbitrary client-supplied forwarding headers.

## Build and test

```sh
./gradlew test
./gradlew build
```

Tests provide isolated credentials and temporary storage; no production environment variables are required.

The API uses Ktor routing, Content Negotiation with kotlinx.serialization, Authentication with JWT, Status Pages, Request Validation, Call Logging, CORS, and Ktor Rate Limit. See the [Ktor JWT documentation](https://ktor.io/docs/server-jwt.html) and [request validation documentation](https://ktor.io/docs/server-request-validation.html).
