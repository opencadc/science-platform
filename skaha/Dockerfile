FROM images.opencadc.org/library/cadc-tomcat:1

RUN dnf -y install dnf-plugins-core \
    && dnf -y config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo \
    && dnf -y install acl attr containerd.io docker-ce docker-ce-cli kubernetes-client which \
    && dnf -y clean all

RUN kubectl version --client

COPY build/libs/skaha.war /usr/share/tomcat/webapps/

COPY src/scripts/* /usr/local/bin/