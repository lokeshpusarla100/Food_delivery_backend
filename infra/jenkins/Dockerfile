FROM jenkins/jenkins:lts-jdk21
USER jenkins
RUN jenkins-plugin-cli --plugins \
    workflow-aggregator \
    github-branch-source \
    git \
    credentials-binding \
    junit \
    pipeline-utility-steps
