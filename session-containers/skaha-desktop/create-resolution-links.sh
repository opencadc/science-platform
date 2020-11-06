#!/bin/bash

echo "running resolution-create script..."

while read p; do
  echo "creating resolution link for: $p"
  executable="${p}.sh"
  desktop="${p}.desktop"
  cp software-scripts/resolution-sh.template tmp/$executable
  cp software-scripts/resolution-desktop.template tmp/$desktop
  sed -i -e "s#(RESOLUTION)#$p#g" tmp/$executable
  sed -i -e "s#(NAME)#$p#g" tmp/$desktop
  rm -f tmp/*-e
done <skaha-resolutions.properties

