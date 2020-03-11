#!/bin/bash

echo "running software-create script..."

while read p; do
  IFS=' = ' read name image <<< "$p"
  echo "$name"
  echo "$image"
  echo "creating software links for: $name"
  if [[ $name != \#* ]]
  then
    executable="${name}.sh"
    desktop="${name}.desktop"
    cp software-scripts/software-sh.template tmp/$executable
    if [[ $name == casa* ]]
    then
      cp software-scripts/software-casa.template tmp/$desktop
    else
      cp software-scripts/software-desktop.template tmp/$desktop
    fi
  fi
  sed -i -e "s#(IMAGE_ID)#$image#g" tmp/$executable
  sed -i -e "s#(NAME)#$name#g" tmp/$desktop
  rm -f tmp/*-e
done <arcade-software.properties

