## building 
This versioin of DS9 launches ds9 directly upon startup, bypassing the xterm.
Update VERSION
```
docker build -t images.canfar.net/skaha/ds9:latest -f Dockerfile .
./apply-version.sh
```

