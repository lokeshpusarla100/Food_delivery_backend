// GitHub Multibranch Pipeline.
// PRs run Maven on a low-privilege test agent (NO Docker socket, registry or SSH credentials).
// Only the verified main branch can build/push images and, after human approval, deploy staging.
def SERVICE_MODULES = [
  'discovery-service', 'api-gateway', 'user-service', 'restaurant-service',
  'order-service', 'payment-service', 'delivery-service', 'notification-service',
  'fleet-management-service', 'feedback-service', 'analytics-service'
]

pipeline {
  agent none
  options {
    skipDefaultCheckout(true)
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 90, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '25', artifactNumToKeepStr: '10'))
  }
  parameters {
    choice(name: 'SERVICE', choices: ['all'] + SERVICE_MODULES,
           description: 'One module (and its Maven dependencies), or every service')
    booleanParam(name: 'DEPLOY_STAGING', defaultValue: false,
                 description: 'Only main: deploy after an explicit human approval')
  }

  stages {
    stage('Verify & package (unprivileged)') {
      agent { label 'food-delivery-test' }
      steps {
        checkout scm
        script {
          if (!(params.SERVICE in (['all'] + SERVICE_MODULES))) error('Invalid SERVICE')
          env.REVISION = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
          env.TARGET_SERVICES = params.SERVICE == 'all'
            ? SERVICE_MODULES.join(' ') : params.SERVICE
          echo "Source commit: ${env.REVISION}; modules: ${env.TARGET_SERVICES}"
        }
        sh '''#!/usr/bin/env bash
set -euo pipefail
if [ "$SERVICE" = "all" ]; then
  mvn -B -ntp verify
else
  mvn -B -ntp -pl "$SERVICE" -am verify
fi
'''
        script {
          // Do not stash potentially untrusted PR artifacts into privileged agents.
          if (env.BRANCH_NAME == 'main' && !env.CHANGE_ID) {
            stash name: 'verified-jars', includes: '**/target/*.jar'
          }
        }
      }
      post {
        always {
          junit allowEmptyResults: true,
                testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/*.jar',
                           fingerprint: true
        }
      }
    }

    stage('Build & publish immutable OCI images (main only)') {
      when {
        beforeAgent true
        expression { env.BRANCH_NAME == 'main' && !env.CHANGE_ID }
      }
      agent { label 'food-delivery-image' }
      steps {
        deleteDir()
        checkout scm
        sh '''#!/usr/bin/env bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$REVISION" || {
  echo "SCM changed after verification. Refusing to publish." >&2
  exit 1
}
'''
        unstash 'verified-jars'
        withCredentials([
          usernamePassword(credentialsId: 'ghcr-food-delivery',
                           usernameVariable: 'REGISTRY_USER',
                           passwordVariable: 'REGISTRY_TOKEN')
        ]) {
          sh '''#!/usr/bin/env bash
set -euo pipefail
umask 077
mkdir -p .docker-auth
export DOCKER_CONFIG="$PWD/.docker-auth"
set +x
printf '%s' "$REGISTRY_TOKEN" | docker login ghcr.io -u "$REGISTRY_USER" --password-stdin
for service in $TARGET_SERVICES; do
  image="ghcr.io/lokeshpusarla100/food-delivery-$service:$REVISION"
  docker build --pull --file ci/Dockerfile.runtime \
      --build-arg SERVICE_NAME="$service" \
      --label "org.opencontainers.image.revision=$REVISION" \
      --tag "$image" .
  docker push "$image"
done
docker logout ghcr.io
'''
        }
      }
      post {
        always {
          sh(script: 'rm -rf .docker-auth', returnStatus: true)
        }
      }
    }

    stage('Deploy staging (human-gated; main only)') {
      when {
        beforeInput true
        expression {
          env.BRANCH_NAME == 'main' && !env.CHANGE_ID && params.DEPLOY_STAGING
        }
      }
      input {
        message "Deploy ${env.REVISION} (${env.TARGET_SERVICES}) to staging?"
        ok 'Deploy staging'
      }
      agent { label 'food-delivery-image' }
      steps {
        checkout scm
        sh 'test "$(git rev-parse HEAD)" = "$REVISION"'
        withCredentials([
          sshUserPrivateKey(credentialsId: 'food-staging-ssh',
                            keyFileVariable: 'DEPLOY_KEY', usernameVariable: 'DEPLOY_USER'),
          file(credentialsId: 'food-staging-known-hosts', variable: 'DEPLOY_KNOWN_HOSTS'),
          string(credentialsId: 'food-staging-host', variable: 'DEPLOY_HOST')
        ]) {
          sh '''#!/usr/bin/env bash
set -euo pipefail
ssh_opts=(-i "$DEPLOY_KEY" -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$DEPLOY_KNOWN_HOSTS")
scp "${ssh_opts[@]}" deploy/compose.staging.yml \
  "$DEPLOY_USER@$DEPLOY_HOST:/opt/food-delivery/compose.staging.yml"
for service in $TARGET_SERVICES; do
  # Registry pull access and secrets are pre-provisioned on the remote host.
  ssh "${ssh_opts[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
    "cd /opt/food-delivery && IMAGE_TAG=$REVISION docker compose --env-file staging.env \
      -f compose.staging.yml pull '$service' && IMAGE_TAG=$REVISION docker compose \
      --env-file staging.env -f compose.staging.yml up -d --no-deps --no-build '$service'"
done
'''
        }
      }
    }
  }
}
