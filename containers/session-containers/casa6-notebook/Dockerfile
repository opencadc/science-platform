ARG ROOT_CONTAINER=jupyter/scipy-notebook:latest
FROM ${ROOT_CONTAINER}

LABEL maintainer="CANFAR Project <support@canfar.net>"

USER root
WORKDIR /tmp

# update base
RUN apt-get update --yes --quiet --fix-missing \
    && apt-get upgrade --yes --quiet

# install bunch of packages
COPY packages.apt .
RUN apt-get install --yes --quiet $(cat packages.apt)
RUN apt-get clean --yes \
    && apt-get autoremove --purge --quiet --yes \
    && rm -rf /var/lib/apt/lists/* /var/tmp/*

# nsswitch for correct sss lookup
ADD nsswitch.conf /etc/

# modify basic environment from jupyter/scipy-notebook
COPY env.yml .

USER ${NB_USER}

RUN mamba remove nomkl --yes
RUN rm ${CONDA_DIR}/conda-meta/pinned

RUN mamba env update --quiet -n base --file env.yml \
    && mamba update --quiet --all --yes \
    && mamba clean --all --quiet --force --yes \
    && fix-permissions ${CONDA_DIR} \
    && fix-permissions /home/${NB_USER}

USER root
ADD pinned ${CONDA_DIR}/conda-meta/pinned
COPY condarc .
RUN cat condarc >> ${CONDA_DIR}/.condarc
RUN fix-permissions ${CONDA_DIR}

WORKDIR ${HOME}
