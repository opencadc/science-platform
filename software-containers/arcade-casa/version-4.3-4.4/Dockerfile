FROM centos:6

# xterm dependency is an extra to get the casa shell in the display
# perl was added for casa later than 5

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
RUN rm -f /opt/${RELEASE}/lib/python2.7/get_user.py
ADD get_user.py /opt/${RELEASE}/lib/python2.7/get_user.py

# chown because the untarred casa has wrong owner/group
RUN chown -R root:root /opt/${RELEASE} && ln -s /opt/${RELEASE} /opt/casa

# create the guest account
#RUN groupadd -g 1001 guest
#RUN useradd -m -g guest -u 1001 guest && \
#    mkdir -p /home/guest/.ssl && \
#    chown -R guest:guest /home/guest/.ssl
#ENV HOME /home/guest
#ENV USER guest

# copy empty casa configs to avoid upgrade
#ADD casa /home/guest/.casa

#WORKDIR /home/guest

RUN yum install -y sssd-client acl

# replace data repository with an up-to-date version for leap second fix (issue #33)
RUN rm -rf /opt/${RELEASE}/data
RUN ln -s /cavern/projects/casa-data-repository/ /opt/${RELEASE}/data

RUN mkdir /skaha
ADD startup.sh /skaha/

# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

ADD nsswitch.conf /etc/

ENTRYPOINT [ "/skaha/startup.sh" ]
