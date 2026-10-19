# Jenkins CI/CD for Food Delivery Backend

This repository provides a Jenkins *pipeline definition* and a local controller container. **It does not provision an external Jenkins controller, build agents, registry credentials, GitHub webhook, databases or deployment infrastructure automatically.** No pipeline has been executed merely by committing this configuration.

## 1. Start a local Jenkins controller (for learning / development)

Prerequisites: Docker Engine, Docker Compose v2. From the repository root:

```bash
docker compose -f infra/jenkins/compose.yml up -d --build
docker compose -f infra/jenkins/compose.yml exec jenkins \
  cat /var/jenkins_home/secrets/initialAdminPassword
```

Open http://localhost:8090 and complete the initial setup.

The controller exposes port **8090 on loopback only**, has a persistent `jenkins_home` Docker volume, and **does not mount** the Docker daemon socket. Do not expose Jenkins directly to the public Internet. For a remotely hosted controller, use TLS termination, identity-based admin access, backup/restore, and a verified GitHub webhook endpoint.

Jenkins image installs `workflow-aggregator`, `github-branch-source`, `git`, `credentials-binding`, `junit`, and `pipeline-utility-steps`. Jenkins and plugin versions should be pinned to tested release digests for a reproducible production install.

## 2. Create dedicated Jenkins agents

Create two separate, disposable Linux worker environments:

| Label | Purpose | Minimum tooling | Security |
| --- | --- | --- | --- |
| `food-delivery-test` | Verify Java code from pull requests and main | JDK 17, Maven 3.9+, Git, bash; Docker/Testcontainers capability when needed | **No** Docker host socket, GHCR push credentials, production secrets, or deployment SSH credentials |
| `food-delivery-image` | Build/push verified main-branch artifacts; SSH to staging | Git, bash, Docker Engine/CLI/Compose v2, SSH and SCP | Dedicated protected build VM/agent; no untrusted PR executions |

Testcontainers-based Maven tests need an isolated Docker-compatible test environment. Use disposable rootless Docker/isolated nested Docker environments; **do not** grant a public PR job unrestricted access to the host Docker socket. Do not run build agents on the Jenkins controller.

A multibranch Jenkinsfile in a PR can be edited by an attacker. In **GitHub Branch Source** configure a trusted Jenkinsfile strategy: do not discover or execute untrusted fork PRs and do not allow untrusted contributor-controlled branches to request `food-delivery-image`. For internet-facing CI, consider a separate protected release job/folder with credentials available only to trusted main-branch builds.

## 3. Configure GitHub repository discovery

1. Create a **Multibranch Pipeline** Jenkins job named `food-delivery-backend`.
2. GitHub Branch Source repository: `lokeshpusarla100/Food_delivery_backend`.
3. Connect a GitHub App (preferred) or a repository-scoped credential with read access.
4. Discover `main` and trusted origin PRs. **Disable untrusted fork PR discovery** until trust policies, sandboxing, and code-source strategy have been audited.
5. Jenkins script path: `Jenkinsfile` at repository root.
6. Set up an HTTPS reverse proxy with a configured Jenkins URL; register a GitHub `push` / `pull_request` webhook to `https://<your-ci-host>/github-webhook/`, or use Jenkins periodic branch indexing for a private local setup.

**Important:** GitHub sees the Jenkinsfile only after PR `feat/jenkins-ci-cd-setup` is merged to main. No webhook or job is created automatically by adding code to GitHub.

## 4. Jenkins credentials (store in Jenkins, never commit)

| Jenkins credential ID | Type | Purpose |
| --- | --- | --- |
| `ghcr-food-delivery` | Username with token (password) | GitHub Container Registry publisher with minimal `write:packages` scope; only use in trusted main build |
| `food-staging-ssh` | SSH username with private key | Restricted staging deployment account |
| `food-staging-known-hosts` | Secret file | Verified SSH host key pins for staging server |
| `food-staging-host` | Secret text | Staging hostname / IP used by SSH |

The staging host separately needs **read-only** registry authentication for any private GHCR packages and private Docker network access to databases, Eureka and Kafka. Keep runtime secrets in a secret manager, not Jenkins parameters or repository files.

## 5. Pipeline behavior

Every build verifies Maven modules and archives JUnit reports/artifacts. `SERVICE` can select one Maven module or `all`. Maven `-pl <module> -am verify` also builds required reactor dependencies.

Only branch `main`, not a PR, may publish OCI images. The image-building worker:
- Checks out the **same exact commit SHA** that successfully passed Maven verification.
- Receives packaged JARs from the prior unprivileged stage via Jenkins `stash/unstash`.
- Builds minimal non-root Java 17 runtime images using `ci/Dockerfile.runtime` (no Maven build inside runtime images).
- Pushes commit-SHA tags (these are naming conventions, not registry-enforced immutability) such as `ghcr.io/lokeshpusarla100/food-delivery-order-service:<40-character-commit-sha>`.

No mutable `latest` tag is published. Commit-SHA tags are a naming convention, not registry-enforced immutability. Before any production rollout, deploy digest-pinned images with signatures/provenance where supported. Never promote an image from an unverified commit.

Set `DEPLOY_STAGING=true` on **main** to request a *human-approved* deployment. Staging deploys only explicitly selected services. No production deployment or automatic rollback is configured.

## 6. Prepare the staging host (not automatic)

1. Install Docker Engine/Compose v2 on a staging VM. Create a restricted SSH deploy account, provision secrets and network controls.
2. Create `/opt/food-delivery`, copy `deploy/staging.env.example` to `/opt/food-delivery/staging.env`, supply **all** real values out of band, and `chmod 600` the file. Never commit the populated file.
3. Create the isolated external Docker network: `docker network create food-platform`.
4. Independently provision MySQL (separate least-privilege DB account **per service**), Redis, Kafka, Zipkin and any other infrastructure on the private network. Ensure DNS aliases `mysql`, `kafka`, and `discovery-service` are resolvable.
5. Configure TLS/reverse proxy in front of the gateway. The staging Compose gateway listens only on localhost port 8080 by default. Do **not** reuse demo-only root MySQL credentials from the original `docker-compose.yml`.
6. Authenticate GHCR on the staging host using a *read-only* token if image packages are private. A host-side image retention/rollback strategy and free-disk-space monitoring are necessary.
7. Pin a verified host key in the Jenkins `food-staging-known-hosts` credential; never disable `StrictHostKeyChecking`.
8. Approve deployment in Jenkins after a successful main-branch build.

Example for a **manual** single image update:

```bash
cd /opt/food-delivery
export IMAGE_TAG='<verified-full-commit-sha>'
docker compose --env-file staging.env -f compose.staging.yml pull order-service
docker compose --env-file staging.env -f compose.staging.yml \
  up -d --no-deps --no-build order-service
```

The staging compose file is **not** the current development `docker-compose.yml`. It expects externally managed private infrastructure and images previously published by Jenkins. It does not create actual secrets, grant cloud IAM permissions, set up DNS, or prove application health.

## 7. Current constraints and next engineering gates

- As of 2026-10-19, User Service PR #1 and Restaurant Service PR #2 have not been merged into main. A main-branch Jenkins build will still use the original service implementations until those changes are reviewed and merged.
- Existing services/modules are incomplete. The Jenkins pipeline **does not bypass failing tests**; if `mvn verify` fails, images will not publish or deploy.
- Before production operations: dedicated DB users, distributed auth, protected secrets, supply-chain image scanning + SBOM/signing, retention, vulnerability patches, CD health checks/rollbacks, observability, staging integration and load testing, disaster recovery, HA, and strict branch protections.
- Swiggy/Zomato-like scale is an unproven goal. A CI/CD pipeline is not evidence of scale or readiness.

## Quick audit

- Jenkins controller Docker socket: **not mounted**.
- Untrusted PR registry publish: **not allowed by Jenkinsfile**; enforce GitHub Branch Source trust policy as well.
- Image tags: **commit SHA**.
- Deployments: **staging only, main only, human approval**.
- Remote SSH: **pinned host key, key-based auth**.
- Release is blocked if Maven verification fails.
