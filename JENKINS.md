# Food Delivery Jenkins setup

For the complete GitHub Multibranch Pipeline and local Jenkins controller instructions, see [docs/jenkins-setup.md](docs/jenkins-setup.md).

- Start a local controller: `docker compose -f infra/jenkins/compose.yml up -d --build`.
- Jenkinsfile: [Jenkinsfile](Jenkinsfile).
- Runtime container build: [ci/Dockerfile.runtime](ci/Dockerfile.runtime).
- Staging deployment template: [deploy/compose.staging.yml](deploy/compose.staging.yml).
- **Jenkins is not yet connected to a live GitHub webhook, build agent or registry; configuration is required outside this repository.**
