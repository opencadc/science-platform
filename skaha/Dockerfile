FROM images.opencadc.org/library/cadc-tomcat:1

ARG KUBECTL_VERSION="v1.25.16"

# install docker
RUN dnf -y install docker

# needed to get/set quota when adding users.
RUN dnf -y install attr

# install kubernetes client
ADD https://storage.googleapis.com/kubernetes-release/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl /usr/local/bin/kubectl

RUN set -x && \
    chmod +x /usr/local/bin/kubectl && \
    \
    # Create non-root user (with a randomly chosen UID/GUI).
    adduser -d /config -u 2342 kubectl && \
    \
    # Basic check it works.
    kubectl version --client

COPY build/libs/skaha.war /usr/share/tomcat/webapps/

COPY src/scripts/* /usr/local/bin/
