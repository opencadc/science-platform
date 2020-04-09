FROM centos:6

RUN yum makecache -y
RUN yum update -y
RUN yum install -y iproute lsof

RUN mkdir /carta
WORKDIR /carta
ENV HOME /carta
ADD https://github.com/CARTAvis/carta-releases/releases/download/v1.3/CARTA-v1.3-remote.tgz /carta/
RUN tar xf CARTA-v1.3-remote.tgz
RUN rm CARTA-v1.3-remote.tgz

# arcade carta startup script
ADD src/arcade-carta /carta/

# customized carta script
RUN rm /carta/CARTA-v1.3-remote/carta
ADD src/carta /carta/CARTA-v1.3-remote/

RUN chmod -R a+rwx /carta

CMD ["/carta/arcade-carta"]
