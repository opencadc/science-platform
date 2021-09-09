FROM centos:6

RUN rm /etc/yum.repos.d/CentOS-Base.repo
ADD CentOS-Base.repo /etc/yum.repos.d/

RUN yum makecache -y
RUN yum update -y
RUN yum install -y iproute lsof sssd-client acl

RUN mkdir /carta
WORKDIR /carta
ENV HOME /carta

# Download CARTA
ADD https://github.com/CARTAvis/carta-releases/releases/download/v1.4/CARTA-v1.4-remote.tgz /carta/
RUN tar xf CARTA-v1.4-remote.tgz
RUN rm CARTA-v1.4-remote.tgz
# If iterating on builds, download the tar file to tmp and comment out above 3 lines and
# uncomment the one below
#ADD tmp/CARTA-v1.4-remote.tgz /carta/

# arcade carta startup script
ADD src/skaha-carta /carta/

# customized carta script
RUN rm /carta/CARTA-v1.4-remote/carta
ADD src/carta /carta/CARTA-v1.4-remote/

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

RUN chmod -R a+rwx /carta

CMD ["/carta/skaha-carta"]
