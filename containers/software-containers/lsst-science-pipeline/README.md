# Building the lsst image

This package makes use of `docker` build targets to build testing and deployment versions.  We also use a Makefile to run the `docker` commands.

## Testing/Development build and run
To build a development version of the container that you will run locally to ensure everything is working.  You can also use this conatiner to run LSST-Pipeline analysis locally.
```
make dev VERSION=o_latest
# Any lsstsqre version tag will do, eg. d_latest, w_latest etc.
docker run --user testuser --interactive --tty --ip 0.0.0.0 --rm --env DISPLAY=host.docker.internal:0 images.canfar.net/lsst/science_pipeline:o_latest xterm  -fg white -bg black -title science_pipeline:o_latest
```
This will launch an xterm running as testuser with access to the lsst pipeline environment. 

The `testuser` account has copied to its home directory the `docker_test.sh` script.  Run this script to test your install. (Take a few minutes.) 

### Note on macOS and X11  
To use the above testuser on OS-X requires having X11 running. On OS-X do the following:
- Install XQuartz (likely you already have)
- start and `xterm`
- set XQuartz->Preferences->Security : Allow conections from network clients.
- type `xhost +` in your xterm window to allow open connections to X11

## Production

This is the version we will load to `images.canfar.net`.  The `make` command builds the production versionof the container and pushes to `CANFAR` 
You may need to do a `docker login` before running this build step, see https://github.com/opencadc/skaha/tree/master/skaha-containers#publishing

To make a full suite of releases deploy each version seperately.

```
make deploy VERSION=o_latest
make deploy VERSION=w_latest
make deploy VERSION=d_latest

```
### Tag on images.canfar.net ###
Once you have loaded the images log into image.canfar.net and tag them ask `desktop-app` images.



