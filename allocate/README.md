# arcade-allocate

This container runs a script to create a skaha and cavern allocation for the given user.

## building

Update VERSION, then:
```
docker build -t bucket.canfar.net/skaha-allocate:latest -f Dockerfile .
./apply-version.sh
```
## configuration

See the configuration requirements in src/skaha-cavern-allocate
