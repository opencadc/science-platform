#!/bin/bash -eux

cd ${HOME}

export DOCKER_STACKS_JUPYTER_CMD="lab"
export NOTEBOOK_ARGS="--debug"
#export NOTEBOOK_ARGS="--debug --collaborative"

#export JUPYTERLAB_DIR="${HOME}/.jupyter"
#mkdir -pv ${JUPYTERLAB_DIR}
#jupyter lab build
#jupyter lab clean
# Import matplotlib the first time to build the font cache.
#export XDG_CACHE_HOME="${HOME}/.cache"
#MPLBACKEND=Agg python -c "import matplotlib.pyplot"
