## building 
Update VERSION
```
docker build -t images.canfar.net/skaha/lsst_v19_0_0:latest -f Dockerfile .
docker run --interactive --tty --rm  --env DISPLAY=host.docker.internal:0 --volume /Users/kavelaarsj/lsst_test_dir:/home/lsst/test images.canfar.net/skaha/lsst_v19_0_0:latest bash
./apply-version.sh
```

