# Containerization

I want a repeatable way to build and start the Currency Quiz API without configuring Java on every machine. Containerization is my first step towards a delivery process. GitHub checks and a Render deployment come later.

## My choices

The [Dockerfile](../../../Dockerfile) uses two stages. A Java 25 JDK stage runs `./gradlew --no-daemon test shadowJar`. The runtime stage contains a Java 25 JRE and the application JAR at `/app/app.jar`. It runs as a non-root user. Keeping the build tools out of the runtime makes the final image smaller and reduces what is available inside it.

The [.dockerignore](../../../.dockerignore) excludes local secrets and generated files from the build context. Credentials are supplied at runtime, so they do not need to be copied into the image.

[Compose](../../../compose.yaml) describes the local service: its port, runtime variables, storage, restart policy and health check. This gives another developer one startup command after creating `.env`:

```sh
docker compose up -d --build --wait
```

Docker must be running. The command builds the image, starts the API in the background and waits for a successful HTTP check at `/`. The [project README](../../../README.md#docker-compose) explains configuration and everyday commands.

## Data and restarts

The container listens on port `8080`; Compose publishes the configured host port on `127.0.0.1`. Questions are written to `/app/data/questions.json` in the named volume `questions-data`. This is separate from the host file used by a native JVM run. A new volume starts with the 40 sample questions.

`docker compose down` keeps the volume. The `unless-stopped` policy resumes the existing container when Docker starts, unless it was manually stopped. After a manual stop or removal, I can run `docker compose up -d` again. [Docker documents this restart behaviour](https://docs.docker.com/engine/containers/start-containers-automatically/).

## Validation

On 29 October 2026, the Docker build passed all 31 tests. The first build stopped because a Swagger test expected different capitalisation in the API title. Updating that assertion fixed the failure while keeping the test stage in place.

The container checks used a separate Compose project with test credentials:

| Check | Result |
|-------|--------|
| Startup and permissions | Healthy HTTP response; application runs as UID `10001`. |
| Quiz and authentication | 40 seeded public questions without answers; admin login, creation and answer checking work. |
| Persistent storage | An added question survives container recreation with the same volume. |
| Runtime configuration | `PORT=10000` works, including the image health check. |
| Image contents | No project `.env` or source directory in the runtime container. |

A successful `/` check shows that the HTTP server responds. The separate API and persistence checks provide evidence for authentication and storage. Render deployment still needs its own validation.

For Render, the same Dockerfile can be used with dashboard environment variables and a persistent disk for question data. This remains a deployment plan. Containerization alone does not provide automated releases or prove production readiness.
