#!/bin/bash -e

echo "Starting VS code session"
echo "HOME: ${HOME}"
echo "USER: $(whoami)"

mkdir -p ~/.config/code-server

echo "bind-addr: 127.0.0.1:8080" > ~/.config/code-server/config.yaml
echo "auth: none" >> ~/.config/code-server/config.yaml
echo "cert: false" >> ~/.config/code-server/config.yaml

/usr/bin/entrypoint.sh --bind-addr 0.0.0.0:5000 ~

echo "Exiting"

