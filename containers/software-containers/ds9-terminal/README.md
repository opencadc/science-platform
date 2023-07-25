## building 
This version of DS9 launches an xterm on startup. A user will launch ds9 using a command line with customized DS9 parameters/options.
Update VERSION
```
docker build -t images.canfar.net/skaha/ds9-terminal:latest -f Dockerfile .
./apply-version.sh
```

