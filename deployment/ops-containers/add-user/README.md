# add-user

This container runs a script to create a skaha and cavern allocation for the given user.

## building

Update VERSION, then:
```
docker build -t images.canfar.net/skaha-system/add-user:latest -f Dockerfile .
./apply-version.sh
```
