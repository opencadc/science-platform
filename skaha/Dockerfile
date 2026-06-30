# To build the image, run the following command:
#   git clone https://github.com/opencadc/science-platform.git
#   cd science-platform && docker build -t skaha:local -f skaha/Dockerfile .
# Note: The build requires the .git directory to be included in the build context.
FROM eclipse-temurin:21-alpine AS base

FROM base AS builder
COPY . /src
WORKDIR /src/skaha
RUN ./gradlew clean spotlessCheck build --no-daemon

FROM base AS otel-javaagent
ARG OTEL_JAVA_AGENT_VERSION=2.29.0
ARG OTEL_JAVA_AGENT_SHA256=546531ca690a8603d2923b6db26bbda35c6409327b1e610430ae33c2f8f68050

RUN set -eux \
    && apk add --no-cache curl \
    && mkdir -p /opt/opentelemetry-javaagent \
    && curl --fail --location --show-error --silent \
        --output /tmp/opentelemetry-javaagent.jar \
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    && echo "${OTEL_JAVA_AGENT_SHA256}  /tmp/opentelemetry-javaagent.jar" | sha256sum --check --status \
    && mv /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar \
    && chmod 0444 /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar

FROM images.opencadc.org/library/cadc-tomcat:1 AS production

RUN set -eux \
    && dnf install --nodocs --assumeyes --setopt=install_weak_deps=False dnf-plugins-core \
    && dnf -y config-manager addrepo --from-repofile=https://download.docker.com/linux/fedora/docker-ce.repo \
    && dnf -y install --nodocs --assumeyes --setopt=install_weak_deps=False \
        acl \
        attr \
        containerd.io \
        docker-ce \
        docker-ce-cli \
        kubernetes-client \
    # Clean up dnf cache and other unneeded files to reduce image size
    && dnf clean all

COPY --from=otel-javaagent /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar
COPY --from=builder /src/skaha/build/libs/skaha.war /usr/share/tomcat/webapps/
