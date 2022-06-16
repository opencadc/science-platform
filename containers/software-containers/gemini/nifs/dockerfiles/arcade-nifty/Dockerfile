# Dockerfile

FROM continuumio/miniconda

RUN dpkg --add-architecture i386
RUN apt-get update -y --fix-missing --allow-releaseinfo-change && \
    apt-get upgrade -y && \
    apt-get install -y xauth locales && \
    /usr/sbin/update-locale LANG=C.UTF-8 && locale-gen C.UTF-8

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

RUN apt-get install -y libc6:i386 libz1:i386 libncurses5:i386 libbz2-1.0:i386 \
    libuuid1:i386 libxcb1:i386 libxmu6 libxss1 libxft2 python-qt4 sssd \
    libnss-sss libpam-sss xterm vim && \
    apt-get remove -y locales && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN conda config --add channels http://ssb.stsci.edu/astroconda && \
    conda create -y -n iraf27 python=2.7 iraf-all pyraf-all stsci && \
    conda clean --all -f -y

ENV PATH /opt/conda/envs/iraf27/bin:$PATH
RUN /bin/bash -c "source activate iraf27 && mkdir iraf && mkdir scratch"

WORKDIR /iraf

RUN /bin/bash -c "source activate iraf27 && mkiraf"

WORKDIR /scratch

# Create entrypoint script
COPY init.sh /skaha/
RUN ["chmod", "+x", "/skaha/init.sh"]

# Arcade container requirements
ADD nsswitch.conf /etc

# Install development version of Nifty4Gemini
# echo $TIMESTAMP to force re-fetch of latest version of Nifty.
ARG TIMESTAMP
RUN echo $TIMESTAMP && \
    pip install --upgrade pip && \
    pip install --no-cache-dir https://github.com/Nat1405/Nifty4Gemini/archive/provenance.tar.gz && \
    pip install --no-cache-dir 'astroquery==0.4' && \
    pip install --no-cache-dir pyvo && \
    pip install vos --upgrade --user

# Grant non-root users permissions
ENV HOME /scratch
RUN /bin/bash -c "chmod -R 777 /iraf && chmod -R 777 /scratch"

ENTRYPOINT [ "/skaha/init.sh" ]
CMD [ "/bin/bash" ]
