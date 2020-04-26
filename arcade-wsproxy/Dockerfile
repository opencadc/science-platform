FROM centos/httpd

RUN yum clean all expire-cache
RUN yum makecache
RUN yum -y --setopt=tsflags=nodocs install sudo mod_ssl docker-client  && \
    yum clean all

# install kubernetes client
ADD https://storage.googleapis.com/kubernetes-release/release/v1.15.6/bin/linux/amd64/kubectl /usr/local/bin/kubectl

RUN set -x && \
    chmod +x /usr/local/bin/kubectl && \
    \
    # Create non-root user (with a randomly chosen UID/GUI).
    adduser -d /config -u 2342 kubectl && \
    \
    # Basic check it works.
    kubectl version --client

# Install kubectl
RUN curl -L -o /usr/bin/kubectl https://storage.googleapis.com/kubernetes-release/release/v1.15.6/bin/linux/amd64/kubectl && \
  chmod +x /usr/bin/kubectl && \
  kubectl version --client

COPY conf/nofiles.conf /etc/security/limits.d/nofiles.conf
COPY conf/sudoers /etc/sudoers

ADD conf/desktop_rewrite.py /www/bin/desktop_rewrite.py
ADD conf/carta_rewrite.py /www/bin/carta_rewrite.py
ADD conf/httpd.conf /etc/httpd/conf/
ADD conf/rewrite.conf /etc/httpd/conf.d/
ADD conf/cors.conf /etc/httpd/conf.d/

ADD conf/index.html /var/www/html/
RUN mkdir /root/kube

EXPOSE 8080
