# arcade
Interactive Science Platform for ALMA processing

## Overview
Arcade is a general purpose science platform built to support ALMA processing requirements.  It allows users to visually interact with docker containers that have been built for specialized tasks.

Because all components of arcade are containers, they can be scaled out on a cluster of nodes to meet the storage and processing demands of the scientific software containers.

![ARCADE-architecture](ARCADE-architecture-0.2.png)

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

