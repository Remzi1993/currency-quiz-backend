# CI/CD Basics

I configured GitHub Actions to validate, test and package the backend on pushes, pull requests and manual runs. The first GitHub execution still needs to be recorded after pushing the workflow.

| Topic          | Current implementation                                                                                                                                         | Evidence                                                                                                             |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Workflow setup | One Ubuntu `checks` job checks out the pinned shared submodule, installs Java 25 and configures Gradle caching. The wrapper is made executable for Linux.      | [ci.yml](../../../.github/workflows/ci.yml)                                                                          |
| Validation     | OpenAPI validation checks the contract before testing. This is contract validation, not Kotlin style linting.                                                  | [build.gradle.kts](../../../build.gradle.kts), [contract](../../../shared/openapi/currency-quiz.v1.yaml)             |
| Tests          | Regression and contract tests check behaviour and HTTP responses. A failing step prevents packaging. Tests supply their own credentials and temporary storage. | [ServerTest.kt](../../../src/test/kotlin/ServerTest.kt), [ContractTest.kt](../../../src/test/kotlin/ContractTest.kt) |
| Build          | `shadowJar` packages the runnable application after validation and tests pass. The workflow does not deploy or control Render's deployment settings.           | [ci.yml](../../../.github/workflows/ci.yml), [Dockerfile](../../../Dockerfile)                                       |

In [GitHub workflow YAML](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows), `on` defines triggers, `jobs` groups runner work, and `steps` defines its sequence. `uses` calls an action; `run` executes a command. Actions are pinned to commits, repository permissions are read-only, and stale runs are cancelled.

The order is validation → test → build: reject an invalid contract or broken behaviour before packaging. A separate code linter and a deployment stage remain gaps in the full lint → test → build → deploy sequence.

To diagnose a failure, open **Actions → Backend CI → checks** and expand the failed step. Contract errors belong to validation; assertion failures belong to tests; compiler or packaging errors can stop the build. Fix the cause locally and push to rerun. The [containerization article](containerization.md) records a local Docker build/test fix; a failed GitHub run followed by a verified fix remains missing evidence.

The [README](../../../README.md#continuous-integration) lists the same local commands for PowerShell 7+ and Bash. GitHub run logs, merge protection and deployment gates are separate from defining this workflow.

Deepening: [CI/CD: Complete Pipeline Design & Implementation](../deepening/ci-cd-complete-pipeline-design-&-implementation.md).
