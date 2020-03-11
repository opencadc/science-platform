FROM centos/httpd

RUN yum clean all expire-cache
RUN yum makecache
RUN yum -y --setopt=tsflags=nodocs install sudo mod_ssl docker-client  && \
    yum clean all

COPY conf/nofiles.conf /etc/security/limits.d/nofiles.conf
COPY conf/sudoers /etc/sudoers

ADD conf/session_rewrite.py /www/bin/session_rewrite.py
ADD conf/httpd.conf /etc/httpd/conf/
ADD conf/rewrite.conf /etc/httpd/conf.d/
ADD conf/cors.conf /etc/httpd/conf.d/

ADD conf/index.html /var/www/html/

EXPOSE 8080
