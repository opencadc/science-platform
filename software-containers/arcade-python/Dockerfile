FROM continuumio/miniconda3

ENV DEBIAN_FRONTEND noninteractive
RUN conda update -n base conda && \
    conda install -y -c astropy astropy astroquery

RUN apt update -y && apt install -y xterm vim
RUN apt install sssd-client acl

RUN pip install vos cadcdata cadctap

CMD ["xterm"]

