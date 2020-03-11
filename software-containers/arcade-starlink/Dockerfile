# Build file for ORACDR [JCMT data processing environment]
FROM continuumio/anaconda3

ENV TERM=linux
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get -y update
RUN apt-get -y install xorg xterm gcc sudo vim
RUN apt-get -y install sssd-client acl

RUN pip install setuptools
RUN pip install future
RUN pip install scipy
RUN pip install astroquery
RUN pip install photutils
RUN pip install emcee
RUN pip install pyspeckit
RUN pip install matplotlib
RUN pip install corner
RUN pip install h5py
RUN pip install spectres
RUN pip install pyvo
RUN pip install starlink-pyhds
RUN pip install starlink-pyast
RUN pip install starlink-pywrapper

RUN curl http://ftp.eao.hawaii.edu/starlink/2018A/starlink-2018A-Linux.tar.gz | tar xzf -

ENV STARLINK_DIR=/star-2018A

RUN sudo wget http://se.archive.ubuntu.com/ubuntu/pool/main/libp/libpng/libpng12-0_1.2.54-1ubuntu1_amd64.deb
RUN sudo dpkg -i libpng12-0_1.2.54-1ubuntu1_amd64.deb

