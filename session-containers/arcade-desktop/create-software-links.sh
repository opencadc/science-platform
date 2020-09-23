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
    if [[ $name == casa-3* ]]
    then
      cp software-scripts/software-casa-3.template tmp/$desktop
    elif [[ $name == casa-4* ]]
    then
      cp software-scripts/software-casa-4.template tmp/$desktop
    elif [[ $name == casa-5* ]]
    then
      cp software-scripts/software-casa-5.template tmp/$desktop
    else
      cp software-scripts/software-desktop.template tmp/$desktop
    fi
  fi
  sed -i -e "s#(IMAGE_ID)#$image#g" tmp/$executable
  sed -i -e "s#(NAME)#$name#g" tmp/$executable
  sed -i -e "s#(NAME)#$name#g" tmp/$desktop
  rm -f tmp/*-e
done <arcade-software.properties

