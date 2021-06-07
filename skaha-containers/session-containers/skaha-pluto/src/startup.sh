#!/bin/bash

UNAME=$1
SESSIONID=$2

echo "Running pluto session $SESSIONID for user $UNAME"

julia -e 'using Pkg; Pkg.add("Pluto"); import Pluto; Pluto.run(require_secret_for_access=false, launch_browser=false, root_url="http://localhost:1234/")'

echo "Exiting"

