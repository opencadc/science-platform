#!/bin/bash -e

echo "Starting pluto session"
echo "HOME: ${HOME}"
echo "USER: $(whoami)"
echo "Ensuring .julia directory exists for this user"
mkdir -p "$HOME/.julia"

# These variables set up new packages to go into the user's persistent .julia directory,
# and keep the project/manifest in their home directory by default,
# while retaining access to the packages we pre-bake into the container.
# Basically, the user will have full control & reproducibility of their packages,
# with the exception of the above pre-baked / compiled ones.


# New pacakges get installed into here:
export JULIA_DEPOT_PATH="$HOME/.julia:$JULIA_DEPOT_PATH"
# Packages can get loaded from both depots
export JULIA_LOAD_PATH="/opt/julia/environments/v1.9/Project.toml:"
# Default project environment kept in the user's home directory
export JULIA_PROJECT="$HOME"

julia -e 'using Pluto; Pluto.run(require_secret_for_access=false, auto_reload_from_file=true, launch_browser=false, host="0.0.0.0", port=5000)'

echo "Exiting"