# Build file for ORACDR [JCMT data processing environment]
#copying centos6 setup from casa4.5-5.8 containers
FROM centos:6 AS deploy
# make sure we get manual pages
RUN sed -i '/tsflags=nodocs/d' /etc/yum.conf
RUN rm /etc/yum.repos.d/CentOS-Base.repo
ADD CentOS-Base.repo /etc/yum.repos.d/
RUN yum reinstall -y shadow-utils

RUN yum clean all -y
RUN yum makecache -y
RUN yum update -y

RUN yum install -y xorg xterm gcc vim
RUN yum install -y sssd-client acl


# system settings and permissions
COPY src/nofiles.conf /etc/security/limits.d/
COPY src/nsswitch.conf /etc/
## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf

RUN yum install -y epel-release
RUN yum install -y python-pip

RUN yum install -y libgfortran
RUN yum install -y glibc

RUN pip install setuptools

RUN curl https://ftp.eao.hawaii.edu/starlink/2021A/RC2/starlink-2021A-Linux_RC2.tar.gz | tar xzf -

#Additional libraries/packages found lacking after initial build & test
RUN yum install -y libpng


#Additional font packages that are needed for gaia, etc
# (list courtesy of Peter Draper. Gaia will not display without these)
RUN yum install -y xorg-x11-fonts-100dpi-7.2-11.el6.noarch
RUN yum install -y xorg-x11-fonts-ISO8859-1-75dpi-7.2-11.el6.noarch
RUN yum install -y xorg-x11-fonts-Type1-7.2-11.el6.noarch
RUN yum install -y xorg-x11-fonts-ISO8859-1-100dpi-7.2-11.el6.noarch
RUN yum install -y xorg-x11-fonts-misc-7.2-11.el6.noarch
RUN yum install -y xorg-x11-font-utils-7.2-11.el6.x86_64

# Add some stuff to make the UNIX environment a bit nicer
RUN yum install -y emacs
RUN yum install -y moreutils
RUN yum install -y man-pages
RUN yum install -v -y man

ENV STARLINK_DIR=/star-2021A

# Fifth try, JJ suggestion
COPY src/starlink.sh /etc/profile.d/
COPY src/startup.sh /skaha/startup.sh
RUN chmod +x /skaha/startup.sh

# Two build sets, deploy and test
FROM deploy as test
RUN echo "Adding a test user to run local testing"
RUN mkdir -p /arc/home
RUN groupadd -g 1001 testuser
RUN useradd -u 1001 -g 1001 -s /bin/bash -d /arc/home/testuser -m testuser
ENTRYPOINT ["/skaha/startup.sh"]


