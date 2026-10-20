# Currency Quiz API

A Ktor REST API for playing a currency quiz without an account and managing questions with an admin JWT. Questions have a text prompt, answer options, and one correct answer. Public responses never include the correct answer; answers are checked on the server.

## Run locally

Use Java 21 or newer to run Gradle. The build uses a Java 21 toolchain and the included Gradle wrapper.

Set these environment variables in PowerShell 7 (with .NET 6 or newer) before starting the server:

```powershell
$env:ADMIN_USERNAME = Read-Host "Admin username"
$env:ADMIN_PASSWORD = Read-Host "Admin password (at least 12 characters)" -MaskInput
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))

# Optional: allow your browser frontend to call the API.
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173"

.\gradlew.bat run
```

The API listens on `http://localhost:8080`. Interactive Swagger documentation is available at `http://localhost:8080/openapi`.

The server requires credentials and a signing secret at startup; there are no default admin credentials. Keep the same JWT secret between restarts if existing tokens should remain valid. Changing it invalidates previously issued tokens.

| Variable | Required | Default / constraints |
| --- | --- | --- |
| `PORT` | No | `8080` |
| `ADMIN_USERNAME` | Yes | Nonblank; at most 100 characters |
| `ADMIN_PASSWORD` | Yes | 12–1024 characters; must not be blank |
| `JWT_SECRET` | Yes | At least 32 UTF-8 bytes; use a random secret |
| `JWT_ISSUER` | No | `currencyquiz-api` |
| `JWT_AUDIENCE` | No | `currencyquiz-admin` |
| `JWT_TOKEN_TTL_SECONDS` | No | `3600`; from 1 to 86400 seconds |
| `CORS_ALLOWED_ORIGINS` | No | Empty; comma-separated HTTP(S) origins, such as `http://localhost:5173,https://quiz.example.com` |
| `QUESTIONS_FILE` | No | `data/questions.json`, relative to the working directory |

[.env.example](.env.example) lists the available variables. A `.env` file is not loaded automatically: export variables in your shell or configure them in your IDE or hosting environment. Do not commit credentials or signing secrets.

## Endpoints

All request bodies use `Content-Type: application/json`.

| Method | Path | Access | Result |
| --- | --- | --- | --- |
| `GET` | `/api/v1/questions` | Public | `200`: question array without answers |
| `GET` | `/api/v1/questions/{id}` | Public | `200`: one question without its answer |
| `POST` | `/api/v1/questions/{id}/answers` | Public | `200`: `{ "correct": true }` or `{ "correct": false }` |
| `POST` | `/api/v1/auth/login` | Admin credentials | `200`: JWT access token |
| `POST` | `/api/v1/questions` | Admin JWT | `201`: created question; `Location` identifies its public URL |
| `PATCH` | `/api/v1/questions/{id}` | Admin JWT | `200`: updated question |
| `DELETE` | `/api/v1/questions/{id}` | Admin JWT | `204`: deleted, without a response body |

Management responses include `correctAnswer`. Send the token as `Authorization: Bearer <accessToken>` when creating, editing, or deleting a question. Tokens expire after the configured lifetime; log in again to obtain a new one.

### Example flow

In another PowerShell terminal, set `ADMIN_USERNAME` and `ADMIN_PASSWORD` to the same credentials used to start the server. Then log in and create a question:

```powershell
$baseUrl = "http://localhost:8080/api/v1"
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" `
    -ContentType "application/json" `
    -Body (@{ username = $env:ADMIN_USERNAME; password = $env:ADMIN_PASSWORD } | ConvertTo-Json)

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

A public question looks like this:

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

| Status | Meaning |
| --- | --- |
| `400` | Invalid JSON, request fields, or question ID |
| `401` | Invalid credentials or missing, expired, or invalid admin token |
| `404` | Question or route does not exist |
| `415` | Unsupported request content type |
| `429` | Rate limit exceeded; respect `Retry-After` |
| `500` | Unexpected server error; internal details are logged and are not returned |

Login allows 5 requests per minute per client IP. Answer submission allows 30 requests per minute per client IP, shared across question IDs. These limits run in memory and reset when the server restarts.

## Storage and deployment

Questions start empty and are saved to `QUESTIONS_FILE` using atomic file replacement where the filesystem supports it. They survive restarts when the same file is retained. Use a persistent writable volume when deploying in a container. The file contains correct answers, so do not serve it as a public static asset or commit it.

This repository is intended for one server process accessing the questions file. Use a database and shared rate limiting before running multiple instances. The local JSON repository is separate from routing so storage can be replaced later.

Expose the API over HTTPS in deployment and keep secrets in your hosting environment. CORS permits only the configured browser origins; it does not grant admin access. Rate limits use the connection's client IP; behind a reverse proxy, configure trusted proxy handling before relying on per-player limits. Never trust arbitrary client-supplied forwarding headers.

## Build and test

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

On macOS/Linux, use `./gradlew` in place of `.\gradlew.bat`. Tests provide isolated credentials and temporary storage; no production environment variables are required.

The API uses Ktor routing, Content Negotiation with kotlinx.serialization, Authentication with JWT, Status Pages, Request Validation, Call Logging, CORS, and Ktor Rate Limit. See the [Ktor JWT documentation](https://ktor.io/docs/server-jwt.html) and [request validation documentation](https://ktor.io/docs/server-request-validation.html).
