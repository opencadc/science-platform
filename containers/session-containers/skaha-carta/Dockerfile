FROM ubuntu:20.04

RUN add-apt-repository ppa:cartavis-team/carta && apt update && apt install carta

RUN mkdir /carta
WORKDIR /carta
ENV HOME /carta

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

CMD ["carta", " --no_browser"]
