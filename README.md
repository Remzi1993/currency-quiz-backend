# Currency quiz API

A Ktor REST API for playing a currency quiz without an account and managing questions with an admin JWT. Questions have a text prompt, answer options, and one correct answer. Public responses never include the correct answer; answers are checked on the server.

## Shared API contract

The [OpenAPI contract](shared/openapi/currency-quiz.v1.yaml) lives in the separate `currency-quiz-shared` repository, included here as the `shared/` Git submodule. It defines the routes, authentication, request bodies, responses and errors. The backend pins a specific shared-repository commit.

Initialize the pinned submodule after cloning, and update it after pulling a changed pin. This command works in **PowerShell 7+ and Linux/Bash**:

```sh
git submodule update --init --recursive
```

The shared repository is currently local only. This workspace has a local URL override pointing to the sibling `currency-quiz-shared` checkout. The relative `.gitmodules` URL, `../currency-quiz-shared.git`, is intended for the future shared remote. Publish that repository before expecting recursive clones to work on another machine.

Normal Gradle builds generate Kotlin DTOs with OpenAPI Generator 7.26.0 into `build/generated/openapi`, in package `info.remzi.currencyquiz.contract`. `test` and `check` also validate the specification. Resource processing bundles the same specification as `documentation.yaml` for Swagger; there is no second hand-maintained copy.

The shared npm package, `@currency-quiz/contract`, uses openapi-typescript 7.13.0 to generate committed declarations at `shared/generated/typescript/schema.d.ts`. The future Vue frontend can import those types from its own `shared/` submodule or install the private package locally as a development dependency. No frontend has been created yet. Generated types do not validate incoming HTTP data at runtime.

When checking or changing the TypeScript contract, install Node.js/npm and run these commands from the backend root. They work in **PowerShell 7+ and Linux/Bash**:

```sh
npm --prefix shared ci
npm --prefix shared run check
```

Edit the OpenAPI source and regenerate instead of editing generated models. See the [shared repository README](shared/README.md) for generation, frontend imports and the contract-update workflow. Commit a shared change before deliberately updating each consumer's submodule pin.

Native Gradle tests, including request/response contract tests, and `shadowJar` have passed. Shared-contract linting, TypeScript generation and the example consumer's type check have also passed. Docker verification is still pending. The frontend and Postman/Bruno CI checks remain future work.

## Run locally

Docker Compose builds and runs the API with Java 25, so you do not need Java or Gradle installed locally. A native run with JDK 25 is also available.

### Configuration

Create local configuration once. In **PowerShell 7+ on Windows**:

```powershell
Copy-Item .env.example .env

# Generate a signing secret, then paste it into JWT_SECRET in .env.
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))

notepad .env
```

In **Linux/Bash**:

```bash
cp .env.example .env
openssl rand -base64 48
```

Open `.env` in your editor and paste the generated value into `JWT_SECRET`.

Set `ADMIN_PASSWORD` to a unique password of at least 12 characters and fill in `JWT_SECRET`. The template sets the username to `admin`; change it if desired. It also sets `CORS_ALLOWED_ORIGINS=*` so developers can call this public API from any browser origin. Replace `*` with explicit frontend origins if you want to restrict browser access.

### Docker Compose

Start Docker with Compose support, then run this from the project root after initializing `shared/` and saving `.env`. The command works in **PowerShell 7+ and Linux/Bash**:

```sh
docker compose up --build --wait
```

The image build runs the tests and packages the application. Compose starts the API in the background and waits for its HTTP health check at `/` to pass. The published port is bound to `127.0.0.1`. Open `http://localhost:8080`, or use the host port set by `PORT` in `.env`.

Compose reads `.env` as runtime environment variables. It sets the container's `PORT` to `8080` and `QUESTIONS_FILE` to `/app/data/questions.json`. The named volume `questions-data` stores the questions outside the container. A new volume receives the 40 sample questions; later admin edits survive container recreation. Your host's `data/questions.json` is separate from this volume.

Useful commands for **PowerShell 7+ and Linux/Bash**:

```sh
docker compose logs -f api
docker compose stop
docker compose up -d
docker compose down
```

`stop` stops the API; `up -d` starts it again. `down` removes the container and network but keeps the question volume. Rebuild after code changes with `docker compose up -d --build --wait`.

The `unless-stopped` policy restarts an existing container when Docker starts, unless you stopped it manually. Enable Docker Desktop startup at sign-in if you want this on Windows. After a manual stop or `down`, run `docker compose up -d` again. See [Docker's restart-policy documentation](https://docs.docker.com/engine/containers/start-containers-automatically/).

### Native JVM

Use JDK 25 and the included Gradle wrapper. After saving `.env`, start the server from the project root. This command works in **PowerShell 7+ and Linux/Bash**:

```sh
./gradlew run
```

For native runs, the application loads `.env` automatically on each start. You do not need to set variables in the terminal again. In IntelliJ, select the shared **Currency Quiz API** run configuration. It runs `info.remzi.currencyquiz.MainKt` with the project root as its working directory. Launching `io.ktor.server.netty.EngineMain` directly bypasses the `.env` loader and can cause a `Missing required configuration: security.adminUsername` error.

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
| `CORS_ALLOWED_ORIGINS`  | No       | Empty application default; `*` allows any browser origin, or use comma-separated HTTP(S) origins. `.env.example` sets `*`. |
| `QUESTIONS_FILE`        | No       | `data/questions.json`, relative to the working directory                                         |

[.env.example](.env.example) lists the supported variables. `.env` is read from the process working directory and supports comments and double-quoted values. Existing OS environment variables and explicit JVM system properties take precedence over values in `.env`. A missing `.env` is allowed when settings come from your IDE or hosting environment; malformed entries cause a configuration error. `.env` is ignored by Git. Do not commit credentials or signing secrets.

### Browser access (CORS)

`CORS_ALLOWED_ORIGINS=*` allows requests from any browser origin, which suits this public developer API. To allow only selected frontends, use a comma-separated list such as `http://localhost:5173,https://quiz.example.com`. When the variable is absent or empty, no cross-origin browser origins are allowed. A list containing `*` and valid explicit origins still allows every origin. Invalid entries are rejected even alongside `*`; partial wildcards such as `*/` and `https://*.example.com` are not supported.

[ApiSettings.kt](src/main/kotlin/ApiSettings.kt) validates the setting, and [Http.kt](src/main/kotlin/Http.kt) maps `*` to Ktor's `anyHost()` or configures the explicit origin list. CORS credentials are disabled (`allowCredentials = false`), so this does not enable cookie-based credentialed requests. Admin operations still require an explicitly supplied bearer JWT, and the existing login and answer-submission rate limits apply.

After changing `.env`, restart a native server. For Docker, rerun the documented `docker compose up -d --build --wait` command so Compose applies the configuration to a recreated service; stopping and starting the old container does not update its environment.

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

### Example flow with Linux/Bash

This equivalent example uses `curl` and `jq`:

```bash
base_url="http://localhost:8080/api/v1"
read -r -p "Admin username: " admin_username
read -r -s -p "Admin password: " admin_password
printf '\n'
login=$(curl --fail-with-body --silent --show-error "$base_url/auth/login" \
  -H 'Content-Type: application/json' \
  --data "$(jq -n --arg username "$admin_username" --arg password "$admin_password" \
    '{username: $username, password: $password}')")
token=$(printf '%s' "$login" | jq -r '.accessToken')
question=$(curl --fail-with-body --silent --show-error "$base_url/questions" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  --data '{"text":"Which currency is used in Japan?","options":["USD","EUR","JPY","GBP"],"correctAnswer":"JPY"}')
question_id=$(printf '%s' "$question" | jq -r '.id')

curl --fail-with-body --silent --show-error "$base_url/questions"
curl --fail-with-body --silent --show-error "$base_url/questions/$question_id/answers" \
  -H 'Content-Type: application/json' --data '{"answer":"JPY"}'
curl --fail-with-body --silent --show-error -X PATCH "$base_url/questions/$question_id" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  --data '{"text":"What is the currency of Japan?"}'
curl --fail-with-body --silent --show-error -X DELETE "$base_url/questions/$question_id" \
  -H "Authorization: Bearer $token"
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

Expose the API over HTTPS in deployment and keep secrets in your hosting environment. CORS permits every browser origin when configured with `*`, or only the explicit origins otherwise; it does not grant admin access. Rate limits use the connection's client IP; behind a reverse proxy, configure trusted proxy handling before relying on per-player limits. Never trust arbitrary client-supplied forwarding headers.

### Render preparation

For a future Render web service, choose Docker and use the repository's root `Dockerfile`. Supply the admin credentials, JWT secret and other runtime settings through Render's dashboard. Render builds the Dockerfile directly; `compose.yaml` is for local use. See [Render's Docker setup](https://render.com/docs/docker).

In the web service's **Environment** settings, set `CORS_ALLOWED_ORIGINS=*`, save the change, and redeploy the service to allow browser clients from any origin. Use explicit origins instead if you want a restricted list. Changing the local `.env` or `.env.example` does not update Render's environment.

Use Render's supplied `PORT`; the application binds to `0.0.0.0`. Set the HTTP health-check path to `/`. See [Render's port requirements](https://render.com/docs/web-services#port-binding) and [health-check settings](https://render.com/docs/health-checks).

Set `QUESTIONS_FILE=/app/data/questions.json` and mount a persistent disk at `/app/data` to retain admin edits across deploys and restarts. Render's persistent disks require a paid service; without a disk, local file changes are lost. See [Render's persistent-disk documentation](https://render.com/docs/disks).

GitHub checks and an actual Render deployment are later steps.

## Build and test

These commands work in **PowerShell 7+ and Linux/Bash**. Generation and OpenAPI validation run as part of the build:

```sh
./gradlew test
./gradlew build
```

Tests provide isolated credentials and temporary storage; no production environment variables are required.

The API uses Ktor routing, Content Negotiation with kotlinx.serialization, Authentication with JWT, Status Pages, Request Validation, Call Logging, CORS, and Ktor Rate Limit. See the [Ktor JWT documentation](https://ktor.io/docs/server-jwt.html) and [request validation documentation](https://ktor.io/docs/server-request-validation.html).

## Learning outcomes

- Designing & Realising 
  - [Secure coding practices](docs/designing-and-realising/secure-coding-practices/OWASP.md)
  - [API design & contract-first development](docs/designing-and-realising/API/API-design-and-contract-first-development.md)
- Managing & Collaborating
  - [Containerization](docs/managing-and-collaborating/ci-cd/containerization.md)
