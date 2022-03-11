FROM jupyter/scipy-notebook:latest

USER root

RUN apt-get -y update && \
    apt-get -y install gcc sudo vim sssd acl && \
    apt-get clean --yes && \
    apt-get autoremove --purge --quiet --yes && \
    rm -rf /var/lib/apt/lists/* /var/tmp/*


# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/
