#!/bin/bash

echo "sourcing admit_start.sh"
source /opt/admit/admit_start.sh

echo "applying leap second patch"
/arcade/update-data.patch /opt/casa

echo "starting xterm $1"
xterm -title $1
echo "exit from xterm $1"