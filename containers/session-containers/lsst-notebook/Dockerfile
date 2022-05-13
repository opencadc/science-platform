FROM lsstsqre/centos:7-stack-lsst_distrib-v21_0_0
USER root

# install git-lfs using package cloud
RUN curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.rpm.sh | bash

# take some build scripts from LSST science platform
# see: https://github.com/lsst-sqre/nublado/tree/master/jupyterlab

# This will be an interactive system, so we do want man pages after all
RUN sed -i -e '/tsflags\=nodocs/d' /etc/yum.conf
RUN yum install -y epel-release && \
	yum repolist && yum -y upgrade && \
	rpm -qa --qf "%{NAME}\n" | xargs yum -y reinstall
# Add some other packages
#  gettext and fontconfig are needed for the TexLive installation
#  jq ... file are generally useful utilities
#  ...and finally enough editors to cover most people's habits
RUN yum -y install sudo git-lfs man man-pages \
	gettext fontconfig perl-Digest-MD5 \
	jq unzip ack screen tmux tree file \
	nano vim-enhanced emacs-nox ed \
        xterm sssd-client acl

# Clear build cache
RUN yum clean all -y

# install ds9, no packages
RUN curl https://ds9.si.edu/download/centos7/ds9.centos7.8.2.1.tar.gz | tar -zx -f - -C /usr/bin/

# jupyterlab stuff
RUN source /opt/lsst/software/stack/loadLSST.bash && \
	setup lsst_distrib && \
	conda install --quiet -y notebook jupyterlab bash_kernel && \
	conda clean --all -f -y && \
	python -m bash_kernel.install

## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
#RUN echo "Set disable_coredump false" > /etc/sudo.conf

# generate missing dbus uuid (issue #47)
RUN dbus-uuidgen --ensure

ADD config/nsswitch.conf /etc/
COPY src/start_lsst.sh /etc/profile.d/

# Copy local files as late as possible to avoid cache busting
# system settings and permissions
COPY config/nofiles.conf /etc/security/limits.d/

ARG NB_USER="jovyan"
ARG NB_UID="1000"
ARG NB_GID="100"

# Copy a script that we will use to correct permissions after running certain commands
COPY src/fix-permissions /usr/local/bin/fix-permissions
RUN chmod a+rx /usr/local/bin/fix-permissions

COPY src/start.sh src/start-notebook.sh src/start-singleuser.sh /usr/local/bin/

RUN mkdir /usr/local/bin/start-notebook.d && \
	ln -s /etc/profile.d/start_lsst.sh /usr/local/bin/start-notebook.d

# Currently need to have both jupyter_notebook_config and jupyter_server_config to support classic and lab
COPY config/jupyter_notebook_config.py /etc/jupyter/

RUN sed -re "s/c.NotebookApp/c.ServerApp/g" \
    /etc/jupyter/jupyter_notebook_config.py > /etc/jupyter/jupyter_server_config.py && \
    jupyter notebook --generate-config && \
    jupyter lab clean

USER ${NB_UID}

ENV HOME=/arc/home/$NB_USER \
	NB_USER=$NB_USER \
	NB_UID=$NB_UID \
	NB_GID=$NB_GID \
	LC_ALL=en_US.UTF-8 \
	LANG=en_US.UTF-8
