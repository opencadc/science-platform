# Building the starlink image

This package makes use of `docker` build targets to build testing and deployment versions.  We also use a Makefile to run the `docker` commands.

## Testing/Development build and run
To build a development version of the container that you will run locally to ensure everything is working.  You can also use this conatiner to run STARLINK analysis locally.
```
make dev
docker run --user testuser --interactive --tty --ip 0.0.0.0 --rm --env DISPLAY=host.docker.internal:0 images.canfar.net/skaha/starlink:2021A xterm  -fg white -bg black -title STARLINK:2021A
```
This will launch an xterm running as testuser with access to the starlink software 

### Note on macOS and X11  
To use the above testuser on OS-X requires having X11 running. On OS-X do the following:
- Install XQuartz (likely you already have)
- start and `xterm`
- set XQuartz->Preferences->Security : Allow conections from network clients.
- type `xhost +` in your xterm window to allow open connections to X11


## Production

This is the version we will load to `images.canfar.net`.  The `make` command builds the production versionof the container and pushes to `CANFAR` 
You may need to do a `docker login` before running this build step, see https://github.com/ijiraq/skaha/tree/master/skaha-containers#publishing

```
make deploy
```
