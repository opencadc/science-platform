#!/bin/bash

# One argument expected: the skaha session ID, which makes up part of the connect url

# HOME is defined at runtime.
cd ${HOME}

code-server \
    --proxy-domain=session/vscode-server/"$1" \
    --disable-telemetry \
    --disable-update-check \
    --disable-workspace-trust \
    --bind-addr=0.0.0.0:8889
    /
