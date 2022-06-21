FROM ubuntu:22.04

ARG PHOSIM_VERSION=5.6.11

# dependencies
ENV DEBIAN_FRONTEND noninteractive
RUN apt-get update --yes --quiet
RUN apt-get install --yes wget build-essential \
    csh zlib1g-dev libcfitsio-dev libfftw3-dev python3 python-is-python3

# build phosim
ENV PHOSIM_VERSION=${PHOSIM_VERSION}
WORKDIR /opt
RUN wget https://bitbucket.org/phosim/phosim_release/get/v${PHOSIM_VERSION}.tar.gz && \
    tar xf v${PHOSIM_VERSION}.tar.gz && \
    mv phosim-phosim_* phosim-${PHOSIM_VERSION} && \
    ln -s phosim-${PHOSIM_VERSION} phosim

COPY setup /opt/phosim/bin/

WORKDIR /opt/phosim
RUN make

# cleaning up build-time packages
RUN rm -f v${PHOSIM_VERSION}.tar.gz && \
    apt-get remove --yes libcfitsio-dev libfftw3-dev zlib1g-dev && \
    apt-get autoremove --purge --yes --quiet && \
    rm -rf /var/lib/apt/lists/* /var/tmp/*

ADD nsswitch.conf /etc/

ENV PATH ${PATH}:/opt/phosim
COPY phosim /opt/phosim

CMD ["phosim","--version"]
