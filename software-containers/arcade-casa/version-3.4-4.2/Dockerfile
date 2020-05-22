FROM hepsw/slc5-base:5.11

# xterm dependency is an extra to get the casa shell in the display
# perl was added for casa later than 5

ADD CentOS-Base.repo /etc/yum.repos.d/
RUN cat /etc/yum.repos.d/CentOS-Base.repo

RUN yum clean all; yum update -y
RUN yum install -y freetype libSM libXi libXrender libXrandr \
	libXfixes libXcursor libXinerama fontconfig \
        libxslt xauth xorg-x11-server-Xvfb dbus-x11 \
	tkinter ImageMagick-c++ xterm perl


# setup all required env variables
ARG RELEASE
ENV RELEASE=${RELEASE}
ENV PATH /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/casa/bin

# untar casa databundle to container
ADD ${RELEASE}.tar.gz /opt/

# patch in fix for get_user.py
RUN rm -f /opt/${RELEASE}/lib64/python2.7/get_user.py
ADD get_user.py /opt/${RELEASE}/lib64/python2.7/get_user.py

# chown because the untarred casa has wrong owner/group
RUN chown -R root:root /opt/${RELEASE} && ln -s /opt/${RELEASE} /opt/casa

RUN yum install -y sssd-client acl which

# apply leap second patch (issue #33)
RUN mkdir /arcade
ADD update-data.patch /arcade/
ADD startup.sh /arcade/
ENV HOME /opt/${RELEASE}

# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

ADD nsswitch.conf /etc/

RUN chmod -R a+rwx /arcade

ENTRYPOINT [ "/arcade/startup.sh" ]
CMD ["xterm", "-e", "casa"]
