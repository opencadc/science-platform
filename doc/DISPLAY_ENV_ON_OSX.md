
# Enable X11 DISPLAY Passing From Local Docker Container On OSX

# Table of Contents
1. [The Steps](#the-steps)

## The Steps

1. Enable "Allow connections from network clients" in XQuartz settings. Relaunch XQuartz.
2. In terminal execute

        > xhost + 127.0.0.1

3. Launch docker run with the option

        -e DISPLAY=host.docker.internal:0

These steps were taken from https://medium.com/@mreichelt/how-to-show-x11-windows-within-docker-on-mac-50759f4b65cb
