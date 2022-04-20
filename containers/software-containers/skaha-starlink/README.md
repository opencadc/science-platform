# Building the starlink image
docker build -t images.canfar.net/skaha/starlink:latest -f Dockerfile .

## apply version tags
```bash
. VERSION && echo "tags: $TAGS" 
for t in $TAGS; do
   docker image tag images.canfar.net/skaha/starlink:latest images.canfar.net/skaha/starlink:$t
done
unset TAGS
docker image list images.canfar.net/skaha/starlink
```
