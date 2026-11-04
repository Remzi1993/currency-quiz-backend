# Currency quiz API

A Ktor REST API for playing a currency quiz without an account and managing questions with an admin bearer JWT or 
protected browser cookies. Questions have a text prompt, answer options, and one correct answer. 
Public responses never include the correct answer; answers are checked on the server.

## Shared API contract

The [OpenAPI contract](shared/openapi/currency-quiz.v1.yaml) lives in the separate `currency-quiz-shared` repository, included here as the `shared/`
Git submodule. It defines the routes, authentication, request bodies, responses and errors. 
The backend pins a specific shared-repository commit.

Clone the backend together with its shared contract. These commands work in **PowerShell 7+ and Linux/Bash**:

```sh
git clone --recurse-submodules https://github.com/Remzi1993/currency-quiz-backend.git
cd currency-quiz-backend
```

`--recurse-submodules` initializes and checks out the submodule at the commit recorded by the backend. 
If you already cloned without that flag, run this from the backend root instead:

```sh
git submodule update --init
```

The fallback also works in both shells. This project has no nested submodules; `--recursive` can be added if nested 
submodules are introduced. See [Git's instructions for cloning projects with submodules](https://git-scm.com/book/en/v2/Git-Tools-Submodules#_cloning_a_project_with_submodules).

The shared repository is published at [currency-quiz-shared](https://github.com/Remzi1993/currency-quiz-shared). 
Each consumer pins its contract revision through the submodule.

Normal Gradle builds generate Kotlin DTOs with OpenAPI Generator into `build/generated/openapi`, in package
`info.remzi.currencyquiz.contract`. The generator version is pinned in the [version catalog](gradle/libs.versions.toml).
`test` and `check` also validate the specification.
Resource processing bundles the same specification as `documentation.yaml` for Swagger; there is no second hand-maintained copy.

The shared npm package, `@currency-quiz/contract`, uses OpenAPI Generator to generate committed declarations at 
`shared/generated/typescript/schema.d.ts`. The Vue frontend imports named model types from its own `shared/` submodule. 
Generated types do not validate incoming HTTP data at runtime.

When checking or changing the TypeScript contract, install Node.js/npm and run these commands from the backend root. 
They work in **PowerShell 7+ and Linux/Bash**:

```sh
npm --prefix shared ci
npm --prefix shared run check
```

Edit the OpenAPI source and regenerate instead of editing generated models. 
See the [shared repository README](shared/README.md) for generation, frontend imports and the contract-update workflow. 
Commit a shared change before deliberately updating each consumer's submodule pin.

Native Gradle tests, including request/response contract tests, and `shadowJar` have passed. Shared-contract linting, 
TypeScript generation and the example consumer's type check have also passed. The Docker image builds and passes local 
Linux health-check and quiz API smoke tests. The frontend and Postman/Bruno CI checks remain future work.

### Update the shared API contract

To adopt the latest contract from GitHub, first commit and push any changes in the shared repository. 
Then run the following from the backend root with JDK 25. These commands work in **PowerShell 7+ and Linux/Bash**:

```sh
git submodule sync --recursive
git submodule update --init --remote -- shared
git diff --submodule=log -- shared
./gradlew test
```

`sync` applies the URLs from `.gitmodules`, including the GitHub URL for `shared`. `--remote` fetches the latest commit 
from the shared repository's default branch. The Gradle test task regenerates the Kotlin models, validates the contract 
and runs the backend tests. Fix any compatibility issues before recording the update.

After reviewing the diff and passing the checks, commit and push the new submodule pin in this backend repository:

```sh
git add shared
git commit -m "Update shared API contract"
git push
```

Repeat the update and checks in the frontend repository so both projects use the intended contract revision. 
After pulling an already committed pin change, use `git submodule update --init` to check out that recorded revision. 
Use `--remote` when deliberately adopting a newer contract. See [Git's submodule documentation](https://git-scm.com/docs/git-submodule).

## Run locally

Docker Compose builds and runs the API with Java 25, so you do not need Java or Gradle installed locally. 
A native run with JDK 25 is also available.

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

Set `ADMIN_USERNAME`, a unique `ADMIN_PASSWORD` of at least 12 characters, and `JWT_SECRET`. 
The template sets `CORS_ALLOWED_ORIGINS=*` for public developer access. 
Cookie-based administration separately requires exact `ADMIN_CORS_ALLOWED_ORIGINS` entries. 
For a frontend at `http://localhost:5173`, keep that entry and set `AUTH_COOKIE_SECURE=false` for local HTTP only. 
Production keeps Secure enabled.

### Docker Compose

Start Docker with Compose support, then run this from the project root after initializing `shared/` and saving `.env`. 
The command works in **PowerShell 7+ and Linux/Bash**:

```sh
docker compose up --build --wait
```

The image build runs the tests, verifies code coverage and packages the application. Compose starts the API in the background and waits for its
HTTP health check at `/` to pass. The published port is bound to `127.0.0.1`. Open `http://localhost:8080`, or use the 
host port set by `PORT` in `.env`.

The [Dockerfile](Dockerfile) starts Java with `--enable-native-access=ALL-UNNAMED` so Netty can load native networking 
libraries on Linux without Java 25 native-access warnings. The application and its dependencies run from one bundled JAR, 
so the permission applies to all classpath code. This follows [Java's native-access configuration](https://docs.oracle.com/en/java/javase/25/migrate/migrating-jdk-8-later-jdk-releases.html). 
It is separate from the Jansi console-color setting used for request logging. The flag is included in the image; 
no extra environment variable is needed.

Compose reads `.env` as runtime environment variables. It sets the container's `PORT` to `8080` and `QUESTIONS_FILE` to 
`/app/data/questions.json`. The named volume `questions-data` stores the questions outside the container. 
A new volume receives the 40 sample questions; later admin edits survive container recreation. 
Your host's `data/questions.json` is separate from this volume.

Useful commands for **PowerShell 7+ and Linux/Bash**:

```sh
docker compose logs -f api
docker compose stop
docker compose up -d
docker compose down
```

`stop` stops the API; `up -d` starts it again. `down` removes the container and network but keeps the question volume. 
Rebuild after code changes with `docker compose up -d --build --wait`.

The `unless-stopped` policy restarts an existing container when Docker starts, unless you stopped it manually. 
Enable Docker Desktop startup at sign-in if you want this on Windows. After a manual stop or `down`, run `docker compose up -d` again. 
See [Docker's restart-policy documentation](https://docs.docker.com/engine/containers/start-containers-automatically/).

### Native JVM

Use JDK 25 and the included Gradle wrapper. After saving `.env`, start the server from the project root. 
This command works in **PowerShell 7+ and Linux/Bash**:

```sh
./gradlew run
```

For native runs, the application loads `.env` automatically on each start. You do not need to set variables in the terminal again. 
In IntelliJ, select the shared **Currency Quiz API** run configuration. 
It runs `info.remzi.currencyquiz.MainKt` with the project root as its working directory. 
Launching `io.ktor.server.netty.EngineMain` directly bypasses the `.env` loader and can cause a `Missing required configuration: security.adminUsername` error.

The API listens on `http://localhost:8080`. Interactive Swagger documentation is available at `http://localhost:8080/openapi`.

Request logging uses a plain-text format with Ktor's default colors disabled in [Application.kt](src/main/kotlin/Application.kt). 
This avoids initializing Jansi's native console support and its Java 25 native-access warning in IntelliJ, 
Gradle and packaged runs. No additional JVM options are needed for this logging configuration.

The server requires credentials and a signing secret at startup; there are no default admin credentials. 
Keep the same JWT secret between restarts if existing tokens should remain valid. 
Changing it invalidates previously issued tokens.

| Variable                         | Required | Default / constraints                                                                                                      |
|----------------------------------|----------|----------------------------------------------------------------------------------------------------------------------------|
| `PORT`                           | No       | `8080`                                                                                                                     |
| `ADMIN_USERNAME`                 | Yes      | Nonblank; at most 100 characters                                                                                           |
| `ADMIN_PASSWORD`                 | Yes      | 12–1024 characters; must not be blank                                                                                      |
| `JWT_SECRET`                     | Yes      | At least 32 UTF-8 bytes; use a random secret                                                                               |
| `JWT_ISSUER`                     | No       | `currencyquiz-api`                                                                                                         |
| `JWT_AUDIENCE`                   | No       | `currencyquiz-admin`                                                                                                       |
| `JWT_TOKEN_TTL_SECONDS`          | No       | `300`; 1–300 seconds for bearer tokens and browser access cookies                                                          |
| `ADMIN_SESSION_IDLE_SECONDS`     | No       | `900`; at least the access lifetime and at most 900 seconds                                                                |
| `ADMIN_SESSION_ABSOLUTE_SECONDS` | No       | `1800`; at least the idle lifetime and at most 1800 seconds                                                                |
| `ADMIN_CORS_ALLOWED_ORIGINS`     | No       | Empty default; explicit comma-separated frontend origins. `*` is rejected.                                                 |
| `AUTH_COOKIE_SECURE`             | No       | `true`; set `false` only for local HTTP development                                                                        |
| `AUTH_COOKIE_SAME_SITE`          | No       | `Strict`; supports `Lax` or `None`, with `None` requiring Secure                                                           |
| `CORS_ALLOWED_ORIGINS`           | No       | Empty application default; `*` allows any browser origin, or use comma-separated HTTP(S) origins. `.env.example` sets `*`. |
| `QUESTIONS_FILE`                 | No       | `data/questions.json`, relative to the working directory                                                                   |

[.env.example](.env.example) lists the supported variables. `.env` is read from the process working directory and 
supports comments and double-quoted values. Existing OS environment variables and explicit JVM system properties take 
precedence over values in `.env`. A missing `.env` is allowed when settings come from your IDE or hosting environment; 
malformed entries cause a configuration error. `.env` is ignored by Git. Do not commit credentials or signing secrets.

### Browser access (CORS)

`CORS_ALLOWED_ORIGINS=*` allows requests from any browser origin, which suits this public developer API. 
To allow only selected frontends, use a comma-separated list such as `http://localhost:5173,https://quiz.example.com`. 
When the variable is absent or empty, no cross-origin browser origins are allowed. A list containing `*` and 
valid explicit origins still allows every origin. Invalid entries are rejected even alongside `*`; 
partial wildcards such as `*/` and `https://*.example.com` are not supported.

[ApiSettings.kt](src/main/kotlin/ApiSettings.kt) validates both origin lists. [Http.kt](src/main/kotlin/Http.kt) sends credentialed CORS only to exact 
`ADMIN_CORS_ALLOWED_ORIGINS` entries. Other permitted public origins receive no credential permission; a public `*` 
never enables cookie administration. Browser-session routes reject untrusted origins even when public CORS is open. 
Every cookie-authenticated write additionally requires the session-bound `X-CSRF-Token` header. 
CORS does not replace authentication or CSRF checks.

After changing `.env`, restart a native server. For Docker, rerun the documented `docker compose up -d --build --wait` 
command so Compose applies the configuration to a recreated service; 
stopping and starting the old container does not update its environment.

## Endpoints

All request bodies use `Content-Type: application/json`.

| Method   | Path                             | Access                                           | Result                                                        |
|----------|----------------------------------|--------------------------------------------------|---------------------------------------------------------------|
| `GET`    | `/api/v1/questions`              | Public                                           | `200`: question array without answers                         |
| `GET`    | `/api/v1/questions/{id}`         | Public                                           | `200`: one question without its answer                        |
| `POST`   | `/api/v1/questions/{id}/answers` | Public                                           | `200`: `{ "correct": true }` or `{ "correct": false }`        |
| `POST`   | `/api/v1/auth/login`             | Admin credentials                                | `200`: JWT access token                                       |
| `POST`   | `/api/v1/auth/session`           | Admin credentials + trusted Origin               | `200`: metadata and HttpOnly access/refresh cookies           |
| `GET`    | `/api/v1/auth/session`           | Access or current refresh cookie                 | `200`: metadata, without extending expiry                     |
| `POST`   | `/api/v1/auth/session/refresh`   | Refresh cookie + CSRF + trusted Origin           | `200`: rotated cookies; deadlines stay fixed                  |
| `POST`   | `/api/v1/auth/session/activity`  | Access cookie + CSRF + trusted Origin            | `200`: updates idle deadline after user activity              |
| `DELETE` | `/api/v1/auth/session`           | Access or refresh cookie + CSRF + trusted Origin | `204`: revoke family and clear both cookies                   |
| `POST`   | `/api/v1/questions`              | JWT or access cookie + CSRF                      | `201`: created question; `Location` identifies its public URL |
| `PATCH`  | `/api/v1/questions/{id}`         | JWT or access cookie + CSRF                      | `200`: updated question                                       |
| `DELETE` | `/api/v1/questions/{id}`         | JWT or access cookie + CSRF                      | `204`: deleted, without a response body                       |

Management responses include `correctAnswer`. Send the token as `Authorization: Bearer <accessToken>` when creating, 
editing, or deleting a question. Tokens expire after the configured lifetime; log in again to obtain a new one.

### Browser authentication and session policy

The Vue admin interface uses opaque HttpOnly cookies instead of storing JWTs in JavaScript-accessible storage. Login, 
reads, rotation and activity return only `{ username, expiresIn, accessExpiresIn, absoluteExpiresIn, csrfToken }`; 
lifetimes are in seconds. Both credentials are host-only, HttpOnly, Secure by default, and `SameSite=Strict`. 
Access is scoped to `/api/v1`; refresh to `/api/v1/auth/session`.

Access lasts at most **5 minutes**. The server requires a new login after **15 minutes without activity**, or 
**30 minutes overall**, whichever comes first. Refresh rotates both credentials and rejects reuse by revoking the session family. 
Neither reads nor refresh reset inactivity; explicit user activity and valid question changes can reset it.
No activity can move the absolute deadline. The frontend renews only before user-requested operations and serializes rotation/login/logout across tabs. 
Failed writes and refreshes are never automatically retried.

Use the frontend's same-origin `/api` proxy to `https://currencyquiz-backend.onrender.com`; leave `VITE_API_BASE_URL` empty. 
Browser cookies then belong to the frontend host. Render still needs that exact frontend origin in `ADMIN_CORS_ALLOWED_ORIGINS`. 
Direct cross-site cookies require `SameSite=None; Secure` and may be blocked by browser policies.

The bounded in-memory store holds at most 1,000 sessions and 128 spent refresh digests per family. 
Logout revokes the family immediately. A restart or Render free-plan spin-down clears browser sessions. 
Multiple instances require a shared atomic store. Bearer JWTs remain separate and have no individual revocation. 
The design, tests, limitations and rationale are documented in [Secure coding practices](docs/designing-and-realising/deepening/secure-coding-practices.md).

### Example flow with PowerShell 7+

In another terminal, enter the same admin credentials saved in `.env`, then log in and create a question. 
The application loads `.env` for itself; it does not populate your terminal's environment.

```powershell
$baseUrl = "http://localhost:8080/api/v1"
$adminUsername = Read-Host "Admin username"
$adminPassword = Read-Host "Admin password" -MaskInput
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" `
    -ContentType "application/json" `
    -Body (@{ username = $adminUsername; password = $adminPassword } | ConvertTo-Json)

# Login returns { accessToken, tokenType: "Bearer", expiresIn: 300 }.
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
  "id": "c72b3aab-633f-4268-88e7-c6ba1c3bb217",
  "text": "Which currency is used in Japan?",
  "options": [
    "Chinese yuan",
    "Japanese yen",
    "South Korean won",
    "Thai baht"
  ]
}
```

### Validation and errors

Question text must contain 1–500 characters after trimming. Supply 2–10 options, each 1–200 characters after trimming, 
with no duplicates when compared without case. The correct answer must match one of those options. 
Submitted answers must contain 1–200 characters after trimming; comparison trims whitespace and ignores case.

PATCH requires at least one field. Omitted or `null` fields keep their previous values. 
The resulting question must satisfy the same rules as a new question: when replacing options, update `correctAnswer` in 
the same request if the old answer is no longer an option. IDs are UUIDs.

A valid but incorrect answer returns `200 OK` with `{"correct":false}`. Errors use a consistent JSON envelope:

```json
{
  "code": "validation_error",
  "message": "Request validation failed",
  "details": ["text must contain 1 to 500 characters"]
}
```

The `details` array is empty when there are no additional validation reasons. The status indicates the failure:

| Status | Meaning                                                                                 |
|--------|-----------------------------------------------------------------------------------------|
| `400`  | Invalid JSON, request fields, or question ID                                            |
| `401`  | Invalid credentials or missing, expired, or invalid admin token                         |
| `403`  | Origin not allowed, or a cookie-authenticated write has invalid/missing CSRF protection |
| `404`  | Question or route does not exist                                                        |
| `415`  | Unsupported request content type                                                        |
| `429`  | Rate limit exceeded; respect `Retry-After`                                              |
| `500`  | Unexpected server error; internal details are logged and are not returned               |

Browser and bearer login share 5 attempts per minute per client IP. Refresh and activity share a separate 30 requests 
per minute per client IP. Answer submission allows 30 requests per minute per client IP across question IDs. 
Limits are process-local and reset on restart.

## Storage and deployment

On startup, a missing or blank `QUESTIONS_FILE` is initialized from the bundled `questions-seed.json` resource. 
Parent directories are created automatically. A blank file includes a zero-byte file or one containing only whitespace. 
Existing question data is preserved, including an empty JSON array (`[]`), so deleting all questions does not restore the samples on restart. 
Invalid nonblank JSON causes startup to fail without replacing the file.

Questions are saved to `QUESTIONS_FILE` using atomic file replacement where the filesystem supports it. 
They survive restarts when the same file is retained. Use a persistent writable volume when deploying in a container. 
The file contains correct answers, so do not serve it as a public static asset or commit it.

`src/main/resources` is packaged into the application's JAR. It is suitable for configuration, static files, or read-only starter data. 
Mutable questions belong in the external `data/questions.json` file so updates can be written and retained across deployments.

[questions.seed.json](src/main/resources/questions-seed.json) contains the 40 sample questions with four choices each. 
The seed is validated before being copied into writable storage; subsequent admin changes update only the external file. 
The sample currency-country mappings were checked on 20 October 2026 against the [SIX ISO 4217 current currency list](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml), published 17 September 2026.

This repository is intended for one server process accessing the questions file. Use a database and shared rate limiting before running multiple instances. 
The local JSON repository is separate from routing so storage can be replaced later.

Expose the API over HTTPS in deployment and keep secrets in your hosting environment. 
CORS permits every browser origin when configured with `*`, or only the explicit origins otherwise; it does not grant admin access. 
Rate limits use the connection's client IP; behind a reverse proxy, configure trusted proxy handling before relying on per-player limits. 
Never trust arbitrary client-supplied forwarding headers.

### Render preparation

For Render, choose Docker and use the repository's root `Dockerfile`. Supply the admin credentials, 
JWT secret and other runtime settings through Render's dashboard. Render builds the Dockerfile directly; `compose.yaml` is for local use. 
After changing the Dockerfile, commit and push the change, then rebuild and redeploy on Render to apply it. 
Keep the image's default startup command so its native-access flag is used. See [Render's Docker setup](https://render.com/docs/docker).

In the web service's **Environment** settings, set `CORS_ALLOWED_ORIGINS=*`, save the change, 
and redeploy the service to allow browser clients from any origin. Use explicit origins instead if you want a restricted list. 
Changing the local `.env` or `.env.example` does not update Render's environment.

For browser administration also set `ADMIN_CORS_ALLOWED_ORIGINS` to the exact deployed frontend origin, 
`AUTH_COOKIE_SECURE=true`, `AUTH_COOKIE_SAME_SITE=Strict`, and `JWT_TOKEN_TTL_SECONDS=300`. Keep `ADMIN_SESSION_IDLE_SECONDS=900` 
and `ADMIN_SESSION_ABSOLUTE_SECONDS=1800`, or shorten them. An older `JWT_TOKEN_TTL_SECONDS=3600` now fails startup validation; 
update it before deploying this version. These environment changes and a live deployment must be applied on Render separately.

Use Render's supplied `PORT`; the application binds to `0.0.0.0`. Set the HTTP health-check path to `/`. 
See [Render's port requirements](https://render.com/docs/web-services#port-binding) and [health-check settings](https://render.com/docs/health-checks).

Set `QUESTIONS_FILE=/app/data/questions.json` and mount a persistent disk at `/app/data` to retain admin edits across deploys and restarts. 
Render's persistent disks require a paid service; without a disk, local file changes are lost. 
See [Render's persistent-disk documentation](https://render.com/docs/disks).

The GitHub workflow defines validation, test and build checks. 
Deployment settings and requiring successful checks before deployment remain separate work.

## Build and test

These commands work in **PowerShell 7+ and Linux/Bash**. Generation and OpenAPI validation run as part of the build:

```sh
./gradlew test
./gradlew check
./gradlew build
```

Tests provide isolated credentials and temporary storage; no production environment variables are required.

All tests use [Kotest](https://kotest.io/docs/framework/framework.html) `FunSpec` and Kotest assertions, running on the
JUnit Platform through the `kotest` engine. [MockK](https://mockk.io/) supplies strict mocks for dependencies;
`coEvery` and `coVerify` stub and verify suspending repository calls. Versions are pinned in
[gradle/libs.versions.toml](gradle/libs.versions.toml). The Kotest IntelliJ plugin is optional for gutter run actions.

Kotest provides the test definitions and its own engine; the JUnit Platform discovers and launches that engine on the JVM.
The project uses Kotest's `kotest-runner-junit6` module, with the
[JUnit 6.1.3 BOM](https://docs.junit.org/6.1.3/running-tests/build-support.html#gradle) aligning all JUnit modules,
including the explicitly declared Platform launcher. The Gradle test task selects the `kotest` engine.

- Unit tests cover configuration, dotenv loading, question validation, credentials/JWTs and browser-session logic.
- Repository tests use temporary files to check real persistence, concurrency and failure atomicity.
- `RoutingTest` uses Ktor's in-process test application and a fresh mocked repository for each case.
- `ServerTest` and `BrowserSessionTest` exercise the application with real temporary storage;
  `ContractTest` validates HTTP interactions against the shared OpenAPI contract.

Create mutable fixtures and mocks inside each test or a factory called by that test. Kotest reuses a spec instance
by default, so sharing mocks across tests would also share recorded calls. Use named `withTests` rows for boundary
cases, direct suspend calls in Kotest tests, and a controlled `Clock` for expiry checks. Tests do not need real-time
sleeps or retry assertions. Keep real persistence and HTTP regression tests alongside isolated unit tests.

To run one spec or force a fresh execution, use these commands in **PowerShell 7+ and Linux/Bash**:

```sh
./gradlew test --tests '*AuthServiceTest'
./gradlew test --rerun
```

[JaCoCo](https://docs.gradle.org/current/userguide/jacoco_plugin.html) instruments the test JVM. Every `test` run
generates an HTML coverage report at `build/reports/jacoco/test/html/index.html` and an XML report at
`build/reports/jacoco/test/jacocoTestReport.xml`. The test-results report is `build/reports/tests/test/index.html`.
Coverage combines all tests in the suite. OpenAPI-generated classes in `info.remzi.currencyquiz.contract` are excluded
from coverage reports and verification; handwritten application code remains included.

`check` and `build` enforce at least **90% line coverage and 80% branch coverage** across that application code.
The thresholds and shared class filter are defined in [build.gradle.kts](build.gradle.kts). A filtered `test --tests`
run produces partial coverage; run the full suite before verifying coverage. To generate reports explicitly or run
only the coverage verification task, use these commands in **PowerShell 7+ and Linux/Bash**:

```sh
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
```

For a fresh full test run, coverage verification and HTML/XML reports in one command, use this in
**PowerShell 7+ and Linux/Bash**:

```sh
./gradlew test --rerun check jacocoTestReport
```

Open the HTML coverage report in your browser. In **PowerShell 7+ on Windows**:

```powershell
Invoke-Item ./build/reports/jacoco/test/html/index.html
```

In **Linux/Bash** with a desktop session:

```bash
xdg-open ./build/reports/jacoco/test/html/index.html
```

You can also open `build/reports/jacoco/test/html/index.html` directly in a browser. Click a package, then a class,
to inspect covered and missed lines and branches.

The [build script](build.gradle.kts) registers OpenAPI Generator's `GenerateTask` and `ValidateTask` directly.
The plugin declaration uses `apply false` to provide those task types without its deprecated `Configuration.setVisible` setup.
Generation validates the contract first and disables the optional [OpenAPI normalizer](https://openapi-generator.tech/docs/customization/#openapi-normalizer),
whose inline-array handling otherwise reports a null schema reference. The current contract generates identical Kotlin models without that preprocessing.

[gradle.properties](gradle.properties) sets `org.gradle.warning.mode=fail`, so Gradle deprecations fail local, CI and Docker builds.
To investigate a future warning with all details, run this command in **PowerShell 7+ and Linux/Bash**:

```sh
./gradlew clean test shadowJar --warning-mode all
```

The [Dockerfile](Dockerfile) temporarily skips debconf's optional preconfiguration hook when installing curl, then restores it.
The slim image has no `apt-utils`; normal package configuration still runs during installation, without adding extra runtime tools.
The generator's worker-isolation explanation and Gradle's configuration-cache suggestion are informational messages.

The API uses Ktor routing, Content Negotiation with kotlinx.serialization, JWT and cookie authentication, Status Pages, 
Request Validation, Call Logging, a selective CORS plugin, and Ktor Rate Limit. 
See the [Ktor JWT documentation](https://ktor.io/docs/server-jwt.html) and [request validation documentation](https://ktor.io/docs/server-request-validation.html).

## Continuous integration

The [Backend CI workflow](.github/workflows/ci.yml) follows the frontend's trigger setup:
pushes to `main` or `dev`, pull requests and manual Actions runs. Feature branches such as `unit-testing` run CI
through their pull request, so a push to that branch does not also start a separate push run.
Feature branches without a pull request can be checked locally or with a manual run.

It initializes the pinned `shared/` submodule, uses Temurin Java 25 and Gradle's GitHub cache, 
then validates OpenAPI → runs tests and verifies coverage with `check` → builds the runnable JAR.
Its Linux runner makes `gradlew` executable because Git currently records it without the executable bit. 
Tests use isolated credentials and temporary files; no production secrets are needed.

Run the same checks from the backend root in **PowerShell 7+ and Linux/Bash**:

```sh
git submodule update --init --recursive
./gradlew openApiValidate
./gradlew check
./gradlew shadowJar
```

The JAR is written to `build/libs/currency-quiz-backend-all.jar`. The workflow uses `--no-daemon` for each Gradle 
invocation and reuses dependency caches between runs. Actions use explicit release tags such as
`actions/checkout@v7.0.1` and `actions/setup-java@v6.1.0`; update the version tag when upgrading.
Repository permissions are read-only,
and a newer run cancels an older run for the same workflow/ref.

The workflow uploads test results and HTML/XML coverage reports as the `test-and-coverage-reports` artifact,
including when a check fails. Reports are retained for 14 days. Docker image builds run the same `check` gate.

To view coverage from a GitHub run, open **Actions → Backend CI → the run → Artifacts**, download
`test-and-coverage-reports` and extract the ZIP. Open `reports/jacoco/test/html/index.html` inside the extracted folder.
Keep the complete HTML report folder together so its stylesheet, images and source pages remain available.

After committing and pushing the workflow, open **Actions → Backend CI** to inspect a run. 
Manual runs become available from the default branch. 
To make a successful check mandatory before merging, add the workflow's `checks` status to the branch ruleset after its first run. 
The workflow packages the app; deployment to Render and deployment gates are configured separately. 
See [CI/CD Basics](docs/managing-and-collaborating/starting-points/ci-cd-basics.md) for the stage order, logs and remaining evidence gaps.

## Learning outcomes

- Designing & Realising 
  - [Secure coding practices](docs/designing-and-realising/deepening/secure-coding-practices.md)
  - [API design & contract-first development](docs/designing-and-realising/deepening/api-design-and-contract-first-development.md)
- Managing & Collaborating
  - [Containerization](docs/managing-and-collaborating/starting-points/containerization.md)
  - [CI/CD Basics](docs/managing-and-collaborating/starting-points/ci-cd-basics.md)
  - [CI/CD & Containerization Fundamentals](docs/managing-and-collaborating/starting-points/ci-cd-and-containerization-fundamentals.md)
  - [CI/CD: Complete Pipeline Design & Implementation](docs/managing-and-collaborating/deepening/ci-cd-complete-pipeline-design-&-implementation.md)
