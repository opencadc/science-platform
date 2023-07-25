# This Dockerfile is used to build a DS9 image based on Centos

FROM centos:7

# This will be an interactive system, so we do want man pages after all
RUN sed -i -e '/tsflags\=nodocs/d' /etc/yum.conf
RUN yum install -y epel-release && \
    yum repolist && yum -y upgrade && \
    rpm -qa --qf "%{NAME}\n" | xargs yum -y reinstall
# Add some other packages
#  jq ... file are generally useful utilities
#  ...and finally enough editors to cover most people's habits
RUN yum -y install sudo git-lfs man man-pages \
    perl-Digest-MD5 \
    jq unzip ack screen tmux tree file \
    nano vim-enhanced emacs-nox ed \
    xterm sssd-client acl

# Clear build cache
RUN yum clean all -y

# install ds9, no packages
RUN curl https://ds9.si.edu/download/centos7/ds9.centos7.8.4.1.tar.gz | tar -zx -f - -C /usr/bin/

# start up DS9 directly, bypassing the xterm
RUN mkdir /skaha
COPY src/startup.sh /skaha/startup.sh
RUN chmod +x /skaha/startup.sh
