FROM cadc-tomcat:latest

# install docker
RUN dnf install -y docker

# install kubernetes client
ADD https://storage.googleapis.com/kubernetes-release/release/v1.22.13/bin/linux/amd64/kubectl /usr/local/bin/kubectl

RUN set -x && \
    chmod +x /usr/local/bin/kubectl && \
    \
    # Create non-root user (with a randomly chosen UID/GUI).
    adduser -d /config -u 2342 kubectl && \
    \
    # Basic check it works.
    kubectl version --client

# Install kubectl
#RUN curl -L -o /usr/bin/kubectl https://storage.googleapis.com/kubernetes-release/release/v1.22.13/bin/linux/amd64/kubectl && \
#  chmod +x /usr/bin/kubectl && \
#  kubectl version --client

COPY build/libs/skaha.war /usr/share/tomcat/webapps/


