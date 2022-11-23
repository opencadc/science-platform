# arcade

skaha is a Java servlet implementation offering a REST API to all skaha functionality.

## building

```
gradle clean build
docker build -t images.canfar.net/skaha-system/skaha:latest -f Dockerfile .
./apply-version.sh
```

## checking it
```
docker run -it --rm images.canfar.net/skaha-system/skaha:latest /bin/bash
```

## running it
```
docker run -d --rm images.canfar.net/skaha-system/skaha:latest
```

## configuration

The following configuration files must be available in /config

### catalina.properties

arcade is run on the cadc-tomcat base container (https://github.com/opencadc/docker-base/tree/master/cadc-tomcat), so must be configured as per the documentation on that page.

### LocalAuthority.properties

For all local services used by skaha, a corresponding LocalAuthority entry must be available.  This file maps the StandardID (representing the function of the service) to the serviceID (representing the instance of the service to consult in the registry).  An example LocalAuthority.properties:

```
ivo://ivoa.net/std/GMS#groups-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/GMS#search-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#users-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#reqs-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#login-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#modpass-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#resetpass-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/UMS#whoami-0.1 = ivo://cadc.nrc.ca/gms
ivo://ivoa.net/std/CDP#delegate-1.0 = ivo://cadc.nrc.ca/cred
ivo://ivoa.net/std/CDP#proxy-1.0 = ivo://cadc.nrc.ca/cred
```

### skaha-software.properties

skaha-software.properties is used in two places:  in skaha to whitelist the available software containers and obtain the container name given the imageID.  Secondly, by skaha-desktop to generate shortcuts for users to launch containers through the arcade API.

```
casa-5.6.1-8 = canfar-registry.cloud.computecanada.ca/arcade-casa:5.6.1-8.el7
starlink-2018A-v0.3 = bucket.canfar.net/arcade-starlink:0.3
astrodebian = sfabbro/astrodebian:0.2.2
firefox = canfar-registry.cloud.computecanada.ca/arcade-firefox:latest
python = canfar-registry.cloud.computecanada.ca/arcade-python:latest
```

### launch-novnc.yaml and launch-desktop-app.yaml

These two kubernetes job configuration files are templates used by skaha to run desktop sessions and science containers.  See the examples in src/examples.

## apply version tags
```bash
. VERSION && echo "tags: $TAGS" 
for t in $TAGS; do
   docker image tag images.canfar.net/skaha-system/skaha:latest images.canfar.net/skaha-system/skaha:$t
done
unset TAGS
docker image list images.canfar.net/skaha-system/skaha
```
