#!/bin/bash

# The Skaha session ID makes up part of the connect URL. The session name is
# injected into the workload and used to distinguish concurrent browser tabs.

SESSION_NAME="${skaha_sessionname:-$(hostname)}"

mkdir -p ${HOME}
cd ${HOME}


mkdir -p ${HOME}/.token

jupyter lab \
	--NotebookApp.base_url=session/notebook/"$1" \
	--NotebookApp.notebook_dir=/ \
	--NotebookApp.allow_origin="*" \
	--ServerApp.ip=0.0.0.0 \
	--ServerApp.port=8888 \
	--no-browser \
	--ServerApp.base_url=session/notebook/"$1" \
	--ServerApp.root_dir=/ \
	--ServerApp.allow_origin="*" \
	--LabApp.app_name="${SESSION_NAME} - JupyterLab" \
	${JUPYTERLAB_ARGS}
