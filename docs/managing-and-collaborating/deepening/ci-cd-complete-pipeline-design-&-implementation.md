# CI/CD: Complete Pipeline Design & Implementation

I want a change to reach production through repeatable checks, a tested container and a controlled release. The backend already has CI checks and containerization. The staging, promotion and rollback stages below are a design, not completed implementation.

This builds on three starting points: [CI/CD Basics](../starting-points/ci-cd-basics.md) explains the workflow and logs; [Containerization](../starting-points/containerization.md) covers the image, runtime and storage; [CI/CD & Containerization Fundamentals](../starting-points/ci-cd-and-containerization-fundamentals.md) connects the terminology to the files.

## Branching strategy

I choose lightweight trunk-based development for this small school project: short-lived feature branches, a pull request and a merge into `main` after checks pass. Frequent small changes should be easier to review and undo. GitFlow would add development and release branches while I currently maintain only one application version.

The repository already uses `main`. Requiring the `checks` result through a GitHub branch ruleset is still to be configured; the workflow alone does not enforce merge protection.

## From commit to production

| Stage                   | Purpose and gate                                                                                                                                                                               | Current status                                                                                                                                                                                        |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Validate                | Check the OpenAPI contract before testing. Code style linting would be a separate check.                                                                                                       | Configured in [ci.yml](../../../.github/workflows/ci.yml); no Kotlin style linter.                                                                                                                    |
| Test                    | Run regression and contract tests. A failure must stop packaging and every later release stage.                                                                                                | Tests block the current JAR build. Deployment gating is not implemented. See [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt) and [ContractTest.kt](../../../src/test/kotlin/ContractTest.kt). |
| Build and publish image | On a successful `main` change, build a `linux/amd64` image, publish it to GitHub Container Registry and record its commit SHA and immutable digest.                                            | [Dockerfile](../../../Dockerfile) exists and runs tests while building. GitHub currently builds a JAR; image building and publishing in CI are planned.                                               |
| Deploy staging          | Deploy that image digest to a separate Render staging service, using staging secrets and question data.                                                                                        | Planned.                                                                                                                                                                                              |
| Verify staging          | Wait for the deployment to finish, then check `/`, public questions, answer submission and rejected unauthenticated management requests. Failure stops promotion.                              | Planned. Existing in-process tests do not replace deployed smoke checks.                                                                                                                              |
| Promote production      | After staging succeeds and a manual approval, deploy the same digest to production. Do not rebuild it. Serialize production releases so two runs cannot overwrite each other's release choice. | Planned.                                                                                                                                                                                              |
| Verify production       | Check health and public API behaviour. Record the healthy release, or redeploy the previous known-good version.                                                                                | Planned.                                                                                                                                                                                              |

The future deployment jobs must depend on successful earlier jobs and only promote `main`. [GitHub environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments) can separate staging and production credentials and protect production approval. The current workflow has no deployment jobs.

During implementation, keep cancellation for superseded CI checks, but queue production releases instead of cancelling an active deployment or verification.

## Consistent environments

The [Dockerfile](../../../Dockerfile) uses a Java 25 JDK to test/package the application and a Java 25 JRE to run it as a non-root user. [Compose](../../../compose.yaml) provides local ports, runtime variables, a health check and persistent storage. It is local configuration, not a Render deployment definition.

For promotion, I would use two Render image-backed services. [Render supports prebuilt images and digest references](https://render.com/docs/deploying-an-image), so staging and production can run the exact tested image. This requires configuring the registry and services; it is not established by the current Dockerfile.

Both environments should use the same configuration keys, health-check path and storage layout, with separate URLs, secrets and data. Each needs its own `QUESTIONS_FILE=/app/data/questions.json`. Staging must never share production's writable question file.

The JSON repository requires one process per data file. [Render persistent disks](https://render.com/docs/disks) require paid services and prevent zero-downtime deploys. That is a cost and availability trade-off to account for before choosing this setup.

## Secrets and release control

I choose environment injection for this small service. [application.yaml](../../../src/main/resources/application.yaml) reads admin credentials and the JWT secret from runtime settings. [Compose](../../../compose.yaml) injects local `.env` values; [.dockerignore](../../../.dockerignore) keeps that file out of the image build. CI tests use their own fixtures and need no production credentials.

For the planned release jobs, deployment credentials belong in GitHub environment secrets; application secrets belong in Render's runtime environment. Staging and production need different admin passwords and JWT signing keys. Pull-request checks should not receive deployment credentials.

Direct hosting auto-deploys must not bypass the release gates. A Git-backed Render service can [wait for CI checks](https://render.com/docs/deploys#integrating-with-ci), but that alone does not implement staging promotion. The proposed image-backed flow would explicitly trigger each deployment and wait for completion; an accepted deploy request is not proof of a healthy release.

## Rollback strategy

I choose redeploying the previous image rather than adding blue-green infrastructure to the current file-based application. The planned procedure is:

1. Before promotion, record the current healthy image digest and commit, check configuration compatibility, and back up question data before any storage-format change.
2. If staging fails, leave production unchanged. If production verification fails, pause further promotions and inspect the deployment logs.
3. Redeploy the previous known-good digest, then repeat the health and public API checks.
4. Record the failed release and recovery result, fix the cause on a short-lived branch, and pass the full pipeline again before promotion.

[Render's rollback documentation](https://render.com/docs/rollbacks) explains why the previous image must remain available and why a digest is safer than a mutable tag. Rolling back application code does not roll back the persistent question file. Restoring a data backup is a separate operation that can discard newer admin edits; it must be assessed and rehearsed on staging.

## What remains to demonstrate

The repository provides automated checks, a tested Docker build definition and runtime configuration. It does not yet demonstrate a complete delivery pipeline. I still need hosted CI evidence, required merge checks, CI image publishing, separate staging/production jobs, successful promotion and a rehearsed rollback. These results should be recorded with the commit, image digest, relevant logs and conclusions.
