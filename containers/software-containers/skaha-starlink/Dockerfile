# Build file for ORACDR [JCMT data processing environment]
#copying centos6 setup from casa4.5-5.8 containers
FROM centos:6
RUN rm /etc/yum.repos.d/CentOS-Base.repo
ADD CentOS-Base.repo /etc/yum.repos.d/

RUN yum clean all -y
RUN yum makecache -y
RUN yum update -y

#Below commands are from the original 2018 install script with Ubuntu backend
#ENV TERM=linux
#ENV DEBIAN_FRONTEND=noninteractive
#RUN apt-get -y update
#RUN apt-get -y install xorg xterm gcc sudo vim
#RUN apt-get -y install sssd-tools acl

RUN yum install -y xorg xterm gcc sudo vim
RUN yum install -y sssd-client acl

#Below commands were not in original script but are listed on Starlink page
# as being necessary for Ubuntu installs
#RUN apt-get -y install gfortran 
#RUN apt-get -y install x11-common 
#RUN apt-get -y install libjpeg62-turbo-dev 
#RUN apt-get -y install libxft-dev 
#RUN apt-get -y install libsm-dev 
#RUN apt-get -y install libxt-dev

RUN yum install -y epel-release
RUN yum install -y python-pip

RUN yum install -y libgfortran
RUN yum install -y glibc


RUN pip install setuptools

#Below all included in initial 2018 install; don't seem to be required
#RUN pip install future
#RUN pip install scipy
#RUN pip install astroquery
#RUN pip install photutils
#RUN pip install emcee
#RUN pip install pyspeckit
#RUN pip install matplotlib
#RUN pip install corner
#RUN pip install h5py
#RUN pip install spectres
#RUN pip install pyvo
#RUN pip install starlink-pyhds
#RUN pip install starlink-pyast
#RUN pip install starlink-pywrapper


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


ENV STARLINK_DIR=/star-2021A

RUN export STARLINK_DIR=/star-2021A

#set up starlink environment variables.
#initially suggested method, doesn't work
#ADD init.sh /skaha/ 
#RUN chmod 755 /skaha/init.sh
#CMD [ "/skaha/init.sh" ]
ADD startup.sh /skaha/ 
RUN chmod 755 /skaha/startup.sh
CMD [ "/skaha/startup.sh" ]

#Next suggested method also does not work
#ADD .bashrc /skaha/ 
#RUN chmod 755 /skaha/.bashrc
#CMD [ "/skaha/.bashrc" ]

#Third try! (doesn't work)
#RUN echo 'source $STARLINK_DIR/etc/profile' >> /etc/bash.bashrc

#Fourth try, new contents in init.sh
#ADD init.sh /skaha/
#ADD starlink.sh /skaha/
#RUN chmod 755 /skaha/init.sh
#CMD [ "/skaha/init.sh" ]

#Fifth try, JJ suggestion
#COPY starlink.sh /etc/profile.d/
#RUN chmod 755 /etc/profile.d/starlink.sh

#Below used for original 2018 version
#RUN sudo wget http://se.archive.ubuntu.com/ubuntu/pool/main/libp/libpng/libpng12-0_1.2.54-1ubuntu1_amd64.deb
#RUN sudo dpkg -i libpng12-0_1.2.54-1ubuntu1_amd64.deb

