This is a place for creating config maps that belong in the skaha-workload namespace.  There hasn't been a need to have an overlay of this directory for keel-dev yet.

For an existing desktop-app without an icon on the desktop, we can follow the steps below to add an icon which a user can use to start up the desktop-app. Note that the steps are performed at deployment time.
1. Add an icon to config/app-icons/. There are two limitations:
   - the icon needs to be in .svg format, e.g. ds9-terminal.svg
   - the version information is not included in the icon file name, i.e. not ds9-terminal:8.4.1.svg
2. Add an entry to config/templates/desktop-apps-icon.properties. Each entry is separated by one or more spaces, e.g. ds9 ds9-terminal topcat topcat-terminal terminal
3. Apply the change. Note that '--server-side' is needed because of the large size of the icon files. 
   kubectl apply -k . --server-side
