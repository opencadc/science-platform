ARG VERSION=o_latest
FROM lsstsqre/centos:${VERSION} as deploy
USER root
RUN sed -i '/tsflags=nodocs/d' /etc/yum.conf
RUN yum reinstall -y shadow-utils
# Add some stuff to make the UNIX environment a bit nicer
RUN yum install -y man-pages man-db man
RUN yum install -y xorg xterm gcc vim emacs


# SKAHA system settings and permissions
RUN yum install -y sssd-client acl
COPY src/nofiles.conf /etc/security/limits.d/
COPY src/nsswitch.conf /etc/
## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf
# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

# setup this container for skaha launching
COPY src/start_lsst.sh /etc/profile.d/
COPY src/startup.sh /skaha/startup.sh
RUN chmod +x /skaha/startup.sh

# Two build sets, deploy and test
FROM deploy as test
RUN echo "Adding a test user to run local testing"
RUN mkdir -p /arc/home
RUN groupadd -g 1001 testuser
RUN useradd -u 1001 -g 1001 -s /bin/bash -d /arc/home/testuser -m testuser
USER testuser
COPY src/docker_test.sh /arc/home/testuser/docker_test.sh
RUN chmod +x /arc/home/testuser/docker_test.sh
ENTRYPOINT ["/skaha/startup.sh"]
