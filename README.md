# arcade
Interactive Science Platform for ALMA processing

## Overview
Arcade is a general purpose science platform built to support ALMA processing requirements.  It allows users to visually interact with docker containers that have been built for specialized tasks.

Because all components of arcade are containers, they can be scaled out on a cluster of nodes to meet the storage and processing demands of the scientific software containers.

![ARCADE-architecture-bigpicture](ARCADE-architecture-bigpicture.png)

## Components

### arcade
The arcade module provides the API for creating arcade sessions and launching applications within that session.  It is a Java war file running in tomcat 8 in a container.

### arcade-wsproxy
arcade-wsproxy is an apache httpd container whose job is to proxy NoVNC traffic to the containers running NoVNC sessions.

### arcade-desktop
arcade-desktop is a container representing an arcade session.  It is a NoVNC implementation, forked and modified from the ConSol project:  https://github.com/ConSol/docker-headless-vnc-container.
The current implementation of arcade-desktop as a NoVNC container may be replaced with another technology at some point.

### arcade-carta
arcade-carta is container installation of Carta 1.3 Remote.  It is another type of session supported in arcade.  Other session types can be added to arcade.

### software-containers
These are some of the astronomy science containers that have been built for arcade.  They run as applications within arcade.  The graphical aspects of the containers are displayed in arcade-desktop by sending the DISPLAY to arcade-desktop.

## Dependencies

arcade relies on a number of other opencadc modules to operate.
* registry (reg) web service (https://github.com/opencadc/reg) -- A registry service will be used to read the capabilities and locations of other web services used by arcade.
* access control (ac) web service (https://github.com/opencadc/ac) -- If the IdentityManager implementation is configured to use cadc-access-control-server for authentication an operational ac web service is required to be running.
* credential delegation (cdp) web service (https://github.com/opencadc/cdp) -- The cdp service is used to obtain users' delegated proxy certificates.
* cavern -- arcade is greatly complimented by running cavern along side it.  (https://github.com/opencadc/vos/tree/master/cavern).  cavern is a vospace implementation where both the data and metadata are based on the contents of a file system.  If the software-containers have access to the cavern file system the contents of that file system can be accessed and shared through the cavern web service.
* posix/sssd -- arcade-desktop and software-containers are run with a SSSD configuration that must point to the same LDAP instance as is used by ac.  When users interact with cavern on the file system the permissions are enforced according to the group membership contained in the LDAP instace.

## Deployment
The current implementation targets a Kubernetes deployment.  In the arcade/src/obsolete directory is a version which targets a Docker deployment.  This is no longer supported.

On session and application launch, arcade will interact with kubernetes to manifest these entities.  Two kubernetes configuration files are required for these operations.  Examples of these files can be found in arcade/src/examples.  The variables in these files are replaced by arcade at runtime.

## Building A CASA container
1. cd into the relevant folder with the build file or create a new folder if necessary
2. update or create the relevant files, e.g. Makefile, Dockerfile, download.sh
3. execute 'make'
### Testing A Container
#### Getting Access To The CASA Container
##### Using The UI
If you have previously created a desktop session, you can use the UI of that session to access your container. There is an 'Applications' tab on the top left hand corner. Select 'Applications->CANFAR->CASA' and then the version under development. An xterm for the selected CASA version will pop up after some time.
#### Performing Tests
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
