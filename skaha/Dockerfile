FROM eclipse-temurin:11-alpine AS base

FROM base AS builder
COPY . /skaha
WORKDIR /skaha
RUN ./gradlew clean spotlessCheck build --no-daemon

FROM images.opencadc.org/library/cadc-tomcat:1.3 AS production

RUN set -eux \
    && dnf install --nodocs --assumeyes --setopt=install_weak_deps=False dnf-plugins-core-4.9.0-1.fc40 \  
    && dnf -y config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo \
    && dnf -y install --nodocs --assumeyes --setopt=install_weak_deps=False \
        acl-2.3.2-1.fc40 attr-2.5.2-3.fc40 \
        containerd.io-1.7.22-3.1.fc40 \
        docker-ce-3:27.3.1-1.fc40 \
        docker-ce-cli-1:27.3.1-1.fc40 \
        kubernetes-client-1.29.9-2.fc40 \
    # Clean up dnf cache and other unneeded files to reduce image size
    && dnf clean all

COPY --from=builder /skaha/build/libs/skaha.war /usr/share/tomcat/webapps/
COPY --from=builder /skaha/src/scripts/* /usr/local/bin/