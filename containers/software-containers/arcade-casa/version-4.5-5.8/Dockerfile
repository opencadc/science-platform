FROM centos:6

# xterm dependency is an extra to get the casa shell in the display
# perl was added for casa later than 5

# Override old repo info with current urls
RUN rm /etc/yum.repos.d/CentOS-Base.repo
ADD CentOS-Base.repo /etc/yum.repos.d/

RUN yum clean all -y
RUN yum makecache -y
RUN yum update -y
RUN yum install -y freetype libSM libXi libXrender libXrandr \
	libXfixes libXcursor libXinerama fontconfig \
        libxslt xauth xorg-x11-server-Xvfb dbus-x11 \
	tkinter ImageMagick-c++ xterm perl autoconf python-sphinx graphviz

# setup all required env variables
ARG CASA_RELEASE
ENV CASA_RELEASE=${CASA_RELEASE}
ENV PATH /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/casa/bin

# untar casa databundle to container
ADD ${CASA_RELEASE}.tar.gz /opt/

# chown because the untarred casa has wrong owner/group
RUN chown -R root:root /opt/${CASA_RELEASE} && ln -s /opt/${CASA_RELEASE} /opt/casa

RUN yum install -y sssd-client acl

# add the admit enhancement (issue #25)
RUN yum install -y tcsh
RUN mkdir /opt/admit
ADD admit /opt/admit
RUN cd /opt/admit && \
    autoconf && ./configure --with-casa-root=/opt/${CASA_RELEASE}

# Allow runtime symlink creation to the casa-data-repository
# Create a dangling symlink to casa-data-repository so that after deployment
# the symlink will link to the actual casa-data-repository in storage.
RUN mkdir -p /arc/projects/casa-data-repository
RUN rm -rf /opt/${CASA_RELEASE}/data && \
    ln -s /arc/projects/casa-data-repository/ /opt/${CASA_RELEASE}/data
RUN chmod 777 /opt/${CASA_RELEASE}
RUN rm -rf /arc

RUN mkdir /skaha
ADD init.sh /skaha/

# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

ADD nsswitch.conf /etc/

CMD [ "/skaha/init.sh" ]
