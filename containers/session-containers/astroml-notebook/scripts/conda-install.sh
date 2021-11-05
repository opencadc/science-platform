#!/bin/bash -eu

env_file=$1
mamba update --all --quiet --yes
mamba env update -n base --file ${env_file}
mamba clean --all --quiet --yes
fix-permissions "${CONDA_DIR}"
fix-permissions "/home/${NB_USER}"
