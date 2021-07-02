#!/bin/bash

echo "Starting pluto session"
echo "HOME: ${HOME}"

julia -e 'using Pkg; Pkg.add("Pluto"); import Pluto; Pluto.run(require_secret_for_access=false, launch_browser=false, host="0.0.0.0")'

echo "Exiting"

