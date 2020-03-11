FROM centos:7

# xterm dependency is an extra to get the casa shell in the display
# perl was added for casa later than 5

RUN yum install -y freetype libSM libXi libXrender libXrandr \
	libXfixes libXcursor libXinerama fontconfig \
        libxslt xauth xorg-x11-server-Xvfb dbus-x11 \
	tkinter ImageMagick-c++ xterm perl


# setup all required env variables
ARG VERSION
ENV VERSION=${VERSION}
ENV RELEASE=casa-pipeline-release-${VERSION}
ENV PATH /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/casa/bin

# untar casa databundle to container
ADD ${RELEASE}.tar.gz /opt/

# chown because the untarred casa has wrong owner/group
RUN chown -R root:root /opt/${RELEASE} && ln -s /opt/${RELEASE} /opt/casa

RUN yum install -y sssd-client acl

ADD nsswitch.conf /etc/

CMD ["xterm", "-e", "casa"]
