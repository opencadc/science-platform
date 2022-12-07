FROM ubuntu:20.04

ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update && yes | unminimize 
RUN apt-get update && yes | apt-get install wget man man-db manpages-posix git \
    build-essential zip unzip xdg-utils less emacs nano xterm vim rsync tree gfortran-9 \
    gimp gimp-gmic acl attr mc subversion
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
RUN conda create -y -n astroconda python=3.7 stsci
RUN conda install astroquery

RUN apt-get update && yes | apt-get install sssd libnss-sss libpam-sss
RUN pip install vos cadcdata cadctap cadcutils

RUN mkdir /skaha
ADD init.sh /skaha/

ADD nsswitch.conf /etc/

CMD ["/skaha/init.sh"]
