# Arcade CASA Container
## Building A CASA container
1. cd into the relevant folder with the build file or create a new folder if necessary
2. update or create the relevant files, e.g. Makefile, Dockerfile, download.sh
3. execute 'make'
### Testing A Container
#### Getting Access To The CASA Container
##### Using The UI
You will need to run the container in a graphical environment, such as arcade, to test the visual components of the CASA containers.
#### Performing Tests
The xterm provides access to the CASA container under development. The following are example steps to test a CASA container.
1. Type the id command and ensure that user name is displayed.
2. Type 
```
source /opt/admit/admint_start.sh
admit
```
   and ensure that information on ADMIT is displayed
3. Run the ```casa``` command and ensure that the logging window opens.
4. Within CASA, type ```import admit``` and ensure that there is no error.
5. Exit CASA. Run the ```casaviewer``` command and ensure that casaviewing windows are displayed.
6. Exit ```casaviewer``` and exit the xterm.