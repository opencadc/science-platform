FROM images.opencadc.org/library/cadc-tomcat:1

# add often used tools
RUN dnf -y install which

# PAM LDAP access
RUN dnf -y install sssd-client acl 

# install docker
RUN dnf -y install docker

# needed to get/set quota when adding users.
RUN dnf -y install attr

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

COPY src/scripts/* /usr/local/bin/
