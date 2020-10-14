# arcade-carta

## About

A CARTA 1.3 session container for arcade based on CARTA-remote (https://github.com/CARTAvis).

A wrapper script, `arcade-carta`, is added to the container that calls the CARTA provided startup script, `carta`.

A modified version of `carta` replaces the original.  This version removes the process blocking commands at the end of the script.

## Building

```
docker build -t harbor.canfar.net/cirada/carta:1.3 -f Dockerfile .
```

## Publishing to the image registry

1. Login to https://harbor.canfar.net/ with your CADC Username/Password by pressing the `LOGIN VIA OIDC PROVIDER` button.
1. Copy your CLI Secret to your clipboard under the `User Profile` menu item in the top right corner of the Harbor portal.
1. Log docker into harbor by typing `docker login <CADC Username>`.  Use the CLI Secret in your clipboard as your password.
1. Push the image to harbor:  `docker push harbor.canfar.net/cirada/carta:1.3`
