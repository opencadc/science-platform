# Arcade CASA Container
## Building a CASA Container
1. `cd` into the relevant folder with the build files 
2. execute `make`
## Testing a CASA Container
You will need to run the container in a graphical environment, such as skaha-desktop, to test the visual components of the CASA containers.

The following steps can be performed in a graphical container environment (such as skaha-desktop) to test that the container was built correctly.
1. Type the `id` command and ensure that user name is displayed.
2. Type the following and ensure that information on ADMIT is displayed
`source /opt/admit/admint_start.sh`
`admit`
3. Run the `casa` command and ensure that the logging window opens.
4. Within CASA, type `import admit` and ensure that there is no error.
5. Exit CASA. Run the `casaviewer` command and ensure that casaviewing windows are displayed.
6. Exit `casaviewer` and exit the xterm.
