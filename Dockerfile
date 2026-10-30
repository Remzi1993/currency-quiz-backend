# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# Windows checkouts can contain CRLF line endings and no executable bit.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src
COPY shared/openapi ./shared/openapi
COPY shared/generators ./shared/generators
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon test shadowJar

FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 quiz \
    && useradd --uid 10001 --gid quiz --no-create-home --shell /usr/sbin/nologin quiz \
    && mkdir -p /app/data \
    && chown quiz:quiz /app/data

COPY --from=build /workspace/build/libs/currency-quiz-backend-all.jar /app/app.jar

ENV PORT=8080 \
    QUESTIONS_FILE=/app/data/questions.json
EXPOSE 8080
USER quiz:quiz

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:${PORT}/" || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
