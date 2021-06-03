FROM lsstsqre/centos:7-stack-lsst_distrib-v19_0_0
USER root
# install git-lfs using package cloud
RUN yum -y install sudo.x86_64
RUN curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.rpm.sh | sudo bash
RUN yum -y install git-lfs

# install ds9, no packages
RUN curl https://ds9.si.edu/download/centos7/ds9.centos7.8.2.1.tar.gz | tar -zx -f - -C /usr/bin/

# install the SSSD-CLIENT needed by Arcade ?
RUN yum install -y sssd-client acl

# system settings and permissions
COPY src/nofiles.conf /etc/security/limits.d/

## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf

# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

ADD nsswitch.conf /etc/

USER lsst
CMD [ "bash" ]

