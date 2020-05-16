# software-containers
The modules here are astronomy containers built to be run in arcade.

## Container Requirements

### SSSD
Containers must have `sssd-client` and `acl` installed so that arcade can run them as the end user.

The file /etc/nsswitch.conf must include the sss module in the passwd, shadow, and group entries.  For example:

```
passwd:     sss files
shadow:     files sss
group:      sss files
```

### xterm
`xterm` must be installed on the container
## Creating a CASA container
This section describes the process of creating a CASA container. The currently supported CASA containers are in the arcade-casa folder. The process iterates over build, deploy and test.
### Building A Container
To build a container, go into the relevant folder with the build files or create a new folder if necessary. There are two main files to building a container: Makefile and Dockerfile.
#### Makefile
This is the main file that controls the build process. This file is updated to add/change/remove a CASA version or to change the build process. It invokes docker to build the container.
#### Dockerfile
This file controls the building of the CASA container. For example, for versions 4.3.x to 4.4.x, the ADMIT package is not added to the container. Changes to the container are made in this file.
#### How To Build
1. cd into the relevant folder with the build file or create a new folder if necessary
2. update or create the relevant files, e.g. Makefile, Dockerfile, download.sh
3. execute 'make'
4. execute 'docker image ls'

Step (3) generates a docker image in the local machine, which is listed in step(4). Here is an example list of generated docker images:
| REPOSITORY | TAG | IMAGE ID | CREATED | SIZE |
| --------------------- | ------- | ---------------- | --------------- | ------- |
| bucket.canfar.net/arcade-casa | 4.5.3-el6 | 0b8c5890921b | 13 hours ago | 4.72GB |
| bucket.canfar.net/arcade-casa | 4.5.2-el6 | 21719143194a | 13 hours ago | 4.72GB |

For work that does not require a UI, for example adding a new package to CASA, we may be able to run the container and test our work at this point. Execute 'docker run -it <docker image ID> /bin/bash' and check if the work is correct.
### Deploying A Container
Since a CASA container runs in Arcade under Kubernetes, the container image generated from the build step needs to be pushed to the repository that Kubernetes uses.
1. log into docker by entering your username and password(this only needs to be done once)
```
docker login bucket.canfar.net
```
2. push the docker image generate in the build step to bucket.canfar.net
```
docker push bucket.canfar.net/arcade-casa:<TAG>
```
3. ssh to canfar-login
```
ssh canfar-login
```
4. pull the image into canfar-login
```
docker pull bucket.canfar.net/arcade-casa:<TAG>
```
5. tag the image with the repository that Kubernetes uses
```
docker tag <docker image ID> canfar-registry.cloud.computecanada.ca/arcade-casa:<TAG>
```
6. push the image to the repository that Kubernetes uses
```
docker push canfar-registry.cloud.computecanada.ca/arcade-casa:<TAG>
```
### Testing A Container
Since a CASA container resides inside Arcade, we need to access an Arcade desktop session. We can either access an existing Arcade session or we can create one.
#### Get A Session
##### Open An Existing Arcade Session
Execute the following command and open a browser using the desktop session URL returned
```
curl -E $A/test-certificates/x509_CADCAuthtest2.pem https://proto.canfar.net/arcade/session
```
##### Create Your Own Desktop Session
Execute the following command and open a browser using the desktop session URL returned
```
curl -E ~/.ssl/cadcproxy.pem https://proto.canfar.net/arcade/session -d "name=mydesktop" -d "type=desktop"
```
#### Get Access The CASA Container
##### Use A Custom Script
We can create a custom script to access the container. We can save the script in our home directory, which persists over sessions. To access the CASA container, we just execute the script. The following is an example of such a script. Just replace the version number (4.7.0-el7) with the version under test. An xterm for the CASA version will pop up after some time.
```
#!/bin/bash

IP_ADDRESS=`hostname --ip-address`

/opt/shibboleth/bin/curl -v -L -k -E /cavern/home/${arcade_username}/.ssl/cadcproxy.pem -d "target-ip=${IP_ADDRESS}" -d "software=canfar-registry.cloud.computecanada.ca/arcade-casa:4.7.0-el7" https://${arcade_hostname}/arcade/session/${VNC_PW}/app
```
##### Use The UI
If you have previously created a desktop session, you can use the UI of that session to access your container. There is an 'Applications' tab on the top left hand corner. Select 'Applications->CANFAR->CASA' and then the version under development. An xterm for the selected CASA version will pop up after some time.
#### Perform Tests
The xterm provides access to the CASA container under development. Perform the necessary tests and repeat the build, deploy and test process if necessary. The following is an example test session on the xterm.
```
cwd: ~
 > id
uid=20005(cadcauthtest2) gid=20005(cadcauthtest2) groups=20005(cadcauthtest2)

cwd: ~
 > source /opt/admit/admit_start.sh

cwd: ~
 > admit
ADMIT        = /opt/admit
    version  = 1.0.8.2
CASA_PATH   = /opt/casa-release-4.7.0-el7 linux admit casa-4.7.0-cadcauthtest2-dotssbdt-ltdw77rk-bmtxn
CASA_ROOT    = /opt/casa-release-4.7.0-el7
    prefix   = /opt/casa-release-4.7.0-el7
    version  = 4.7.0
    revision = 38335

cwd: ~
 > casa

=========================================
The start-up time of CASA may vary
depending on whether the shared libraries
are cached or not.
=========================================

CASA Version 4.7.0-REL (r38335)
  Compiled on: Wed 2016/09/28 11:45:08 UTC
___________________________________________________________________
    For help use the following commands:
    tasklist               - Task list organized by category
    taskhelp               - One line summary of available tasks
    help taskname          - Full help for task
    toolhelp               - One line summary of available tools
    help par.parametername - Full help for parameter name
___________________________________________________________________
Activating auto-logging. Current session state plus future input saved.
Filename       : ipython-20200516-201842.log
Mode           : backup
Output logging : False
Raw input log  : False
Timestamping   : False
State          : active
*** Loading ATNF ASAP Package...
*** ... ASAP (rev#38164) import complete ***

CASA <2>: import admit

CASA <3>: exit
--------> exit()
leaving casa...

cwd: ~
 > casaviewer

cwd: ~
```
### Updating The Config Map
If your work requires
```
arcade-k8s-config/arcade/arcade-tomcat/config/arcade-software.properties
```
to be updated, for example, you are adding a new version of CASA container, then prior to releasing the new version of the software, the config map will need to be updated. Follow the steps below.
1. ssh to canfar-login
2. git clone the software under development
3. update the config file
```
arcade-k8s-config/arcade/arcade-tomcat/config/arcade-software.properties
```
4. delete the current config map by executing
```
kubectl delete configmap arcade-config
```
5. create the new config map by executing
```
arcade-k8s-config/arcade/arcade-tomcat/create-config.sh
```
The command may complain about one of the config items already being present. This complain can be ignored.
