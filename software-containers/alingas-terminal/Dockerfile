FROM ubuntu:20.04

ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update && yes | unminimize 
RUN apt-get update && yes | apt-get install wget \
    build-essential xterm vim rsync mlocate

SHELL ["/bin/bash", "-c"]

RUN apt-get update && yes | apt-get install sssd libnss-sss libpam-sss

RUN mkdir /arcade
ADD launch-software.sh /arcade
#ADD arcade-init.sh /arcade/

ADD nsswitch.conf /etc/
