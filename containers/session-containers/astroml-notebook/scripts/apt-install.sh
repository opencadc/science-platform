#!/bin/bash

# package installer for apt based Dockerfiles
# 1. Install apt packages silently
# 2. Update metadata before install
# 3. Remove unneeded files after install

[[ $# == 0 ]] && echo "usage: apt-install.sh [packages...]" >&2 && exit 0

if [[ -f $1 ]]; then
    packages="$(cat $1)"
else
    packages="$*"
fi
set -eu

apt-get update --yes -qq
apt-get update --yes --fix-missing
DEBIAN_FRONTEND=noninteractive apt-get install --yes ${packages}
apt-get autoremove --purge -y
apt-get clean --yes
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
