# To build the image, run the following command:
#   git clone https://github.com/opencadc/science-platform.git
#   cd science-platform && docker build -t skaha:local -f skaha/Dockerfile .
# Note: The build requires the .git directory to be included in the build context.
FROM eclipse-temurin:21-alpine AS base

FROM base AS builder
COPY . /src
WORKDIR /src/skaha
RUN ./gradlew clean spotlessCheck build --no-daemon

FROM ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.29.0@sha256:5c3b76d34492efbd3166bdcfacfc0154b071bb323c3734ebb018223170d52e29 AS otel-javaagent

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

COPY --from=otel-javaagent /javaagent.jar /opt/opentelemetry-javaagent/opentelemetry-javaagent.jar
COPY --from=builder /src/skaha/build/libs/skaha.war /usr/share/tomcat/webapps/
