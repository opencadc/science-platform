'Application->AstroSoftware' menu build process

When skaha-desktop is launched, start-desktop.sh invokes build-menu.sh which is responsible for building the AstroSoftware menu. The AstroSoftware menu consists of a file named astrosoftware.menu which resides in /etc/xdg/menus/applications-merged/. in the skaha-desktop container. The content of astrosoftware.menu is automatically merged to the Application menu on startup.

The astrosoftware.menu is made up of project names and names of desktop apps belonging to each project. These names are available in each desktop app image ID. build-menu.sh uses the Skaha API to obtain a list of all desktop app image IDs. It then iterates through the list, extracts the project name and desktop app name from each image ID and inserts them into astrososoftware.menu.

When a user selects an item in the astrosoftware.menu, the selected desktop app is launched. For this to happen, for each desktop app build-menu.sh uses the templates in /desktopstartup to create a .desktop file and a .sh file in ~/.local/skaha/share/applications/. and ~/.local/skaha/bin/. respectively. For a desktop app with an icon on the skaha desktop, a .desktop file is needed in /headless/Desktop/. build-menu.sh creates this .desktop file which points to the same .sh file in ~/.local/skaha/bin/.

For each desktop app project, build-menu.sh creates a .directory file needed by xfce4 to build the AstroSoftware menu. These .directory files reside in ~/.local/skaha/share/desktop-directories/.
