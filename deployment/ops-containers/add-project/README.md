# add-project

This container runs a script to create a skaha and cavern allocation for the given project.

## building

Update VERSION, then:
```
docker build -t images.canfar.net/skaha-system/add-project:latest -f Dockerfile .
./apply-version.sh
```
