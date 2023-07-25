## building 
This version of topcat launches topcat directly upon startup, bypasing the xterm.
Update VERSION
```
docker build -t images.canfar.net/skaha/topcat:latest -f Dockerfile .
./apply-version.sh
```

