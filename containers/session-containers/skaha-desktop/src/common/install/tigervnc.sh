#!/usr/bin/env bash
set -e

echo "Install TigerVNC server"
wget -qO- https://sourceforge.net/projects/tigervnc/files/stable/1.8.0/tigervnc-1.8.0.x86_64.tar.gz/download | tar xz --strip 1 --no-same-owner --no-same-permissions -C /
