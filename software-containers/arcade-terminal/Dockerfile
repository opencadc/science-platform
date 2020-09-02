FROM ubuntu:20.04

ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update && yes | unminimize 
RUN apt-get update && yes | apt-get install wget man man-db manpages-posix git \
    build-essential zip unzip less emacs nano xterm vim rsync gfortran-9
RUN ln -s /usr/bin/gfortran-9 /usr/bin/gfortran
RUN wget https://repo.anaconda.com/archive/Anaconda3-2020.07-Linux-x86_64.sh -O /tmp/anaconda.sh

SHELL ["/bin/bash", "-c"]
ENV CONDA_HOME=/opt/anaconda \
    PATH=/opt/anaconda/bin:$PATH

RUN bash /tmp/anaconda.sh -b -p $CONDA_HOME
RUN rm -f /tmp/anaconda.sh 
RUN conda update conda 
RUN conda config --add channels http://ssb.stsci.edu/astroconda 
RUN conda config --add channels astropy
RUN conda create -n astroconda stsci
RUN conda install astroquery

RUN apt-get update && yes | apt-get install sssd libnss-sss libpam-sss
RUN pip install vos cadcdata cadctap cadcutils

RUN mkdir /arcade
ADD startup.sh /arcade/

ADD nsswitch.conf /etc/

ENTRYPOINT [ "/arcade/startup.sh" ]
