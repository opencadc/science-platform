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
### How To Build
1. cd into the relevant folder with the build file or create a new folder if necessary
2. update or create the relevant files, e.g. Makefile, Dockerfile, download.sh
3. execute 'make'
### Testing A Container
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
