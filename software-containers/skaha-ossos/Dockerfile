FROM continuumio/anaconda2
RUN apt update
RUN apt install gcc -y
RUN apt install xvfb -y
RUN apt install saods9 -y
RUN apt install sssd-client acl -y
RUN conda config --add channels http://ssb.stsci.edu/astroconda 
RUN conda install stsci -y
RUN conda install wxPython==3.0.0.0 -y
RUN conda install cython -y
RUN conda install iraf-all -y
RUN conda install pyraf-all -y
RUN conda install pyds9 -y
RUN pip install ossos

RUN apt update -y && apt install -y xterm vim

