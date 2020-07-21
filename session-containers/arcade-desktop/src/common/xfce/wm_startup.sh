#!/usr/bin/env bash
### every exit != 0 fails the script
set -e

echo -e "\n------------------ startup of Xfce4 window manager ------------------"
echo -e "\n DISPLAY: $DISPLAY"

### disable screensaver and power management
xset -dpms &
xset s noblank &
xset s off &

/usr/bin/startxfce4 --replace > $XDG_CONFIG_HOME/wm.log &
sleep 1
cat $XDG_CONFIG_HOME/wm.log
