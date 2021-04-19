# skaha-desktop

The core of this project was forked and extended from the ConSol project: https://github.com/ConSol/docker-headless-vnc-container

Modifications include a customized desktop, enabling remote DISPLAY, disabling internal browsers.

# Building

To build the docker images for skaha-desktop, set the version number in VERSION, then:
```
make
./applyVersion.sh
```
