#!/bin/bash

HOST=$1
STARTUP_DIR="/dockerstartup"
EXECUTABLE_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
DIRECTORIES_DIR="$HOME/.local/share/desktop-directories"
START_APPLICATIONS_MENU="${STARTUP_DIR}/template/xfce-applications-top.menu"
END_APPLICATIONS_MENU="${STARTUP_DIR}/template/xfce-applications-bottom.menu"
START_ASTROSOFTWARE_MENU="${STARTUP_DIR}/template/astrosoftware-top.menu"
END_ASTROSOFTWARE_MENU="${STARTUP_DIR}/template/astrosoftware-bottom.menu"
MERGED_DIR="/etc/xdg/menus/applications-merged"
ASTROSOFTWARE_MENU="${MERGED_DIR}/astrosoftware.menu"

init_dir () {
  if [[ -d "$1" ]]; then
    rm -f $1/*
  else
    mkdir -p "$1"
  fi
}

init () {
  dirs="${EXECUTABLE_DIR} ${DESKTOP_DIR} ${DIRECTORIES_DIR}"
  for dir in ${dirs}; do
    init_dir ${dir}
  done
}

build_resolution_items () {
  RESOLUTION_SH="${STARTUP_DIR}/template/resolution-sh.template"
  RESOLUTION_DESKTOP="${STARTUP_DIR}/template/resolution-desktop.template"
  if [[ -f "${RESOLUTION_SH}" ]]; then 
    if [[ -f "${RESOLUTION_DESKTOP}" ]]; then 
      while IFS= read -r line; do
        executable="${EXECUTABLE_DIR}/${line}.sh"
        desktop="${DESKTOP_DIR}/${line}.desktop"
        cp ${RESOLUTION_SH} ${executable}
        cp ${RESOLUTION_DESKTOP} ${desktop}
        sed -i -e "s#(RESOLUTION)#${line}#g" ${executable}
        rm -f ${EXECUTABLE_DIR}/*-e
        sed -i -e "s#(NAME)#${line}#g" ${desktop}
        sed -i -e "s#(HOME)#${HOME}#g" ${desktop}
        rm -f ${DEKSTOP_DIR}/*-e
      done < ${STARTUP_DIR}/template/skaha-resolutions.properties
    else
      echo "[skaha] ${RESOLUTION_DESKTOP} does not exist" 
    fi
  else
    echo "[skaha] ${RESOLUTION_SH} does not exist"
  fi
}

build_resolution_menu () {
  RESOLUTION="resolution"
  cp ${STARTUP_DIR}/template/xfce-directory.template ${DIRECTORIES_DIR}/xfce-${RESOLUTION}.directory
  sed -i -e "s#(NAME)#Resolution#g" ${DIRECTORIES_DIR}/xfce-${RESOLUTION}.directory
  cp ${DIRECTORIES_DIR}/xfce-${RESOLUTION}.directory ${DIRECTORIES_DIR}/${RESOLUTION}.directory
  rm -f ${DIRECTORIES_DIR}/*-e
  build_resolution_items
}

create_merged_applications_menu () {
  if [[ -f "${START_ASTROSOFTWARE_MENU}" ]]; then 
    if [[ -f "${ASTROSOFTWARE_MENU}" ]]; then
      rm -f ${ASTROSOFTWARE_MENU}
    fi
    cp ${START_ASTROSOFTWARE_MENU} ${ASTROSOFTWARE_MENU}
    cp ${STARTUP_DIR}/template/xfce-directory.template ${DIRECTORIES_DIR}/xfce-canfar.directory
    sed -i -e "s#(NAME)#AstroSoftware#g" ${DIRECTORIES_DIR}/xfce-canfar.directory
    rm -f ${DIRECTORIES_DIR}/*-e
    cp ${DIRECTORIES_DIR}/xfce-canfar.directory ${DIRECTORIES_DIR}/canfar.directory
  else
    echo "[skaha] ${START_ASTROSOFTWARE_MENU} does not exist"
  fi
}

complete_merged_applications_menu () {
  if [[ -f "${ASTROSOFTWARE_MENU}" ]]; then
    cat ${END_ASTROSOFTWARE_MENU} >> ${ASTROSOFTWARE_MENU}
    build_resolution_menu
  else
    echo "[skaha] ${ASTROSOFTWARE_MENU} does not exist"
  fi
}

build_menu () {
  project=$1
  directory="xfce-$1.directory"
  cat ${STARTUP_DIR}/template/xfce-applications-menu-item.template >> ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(NAME)#${project}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(DIRECTORY)#${directory}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(CATEGORY)#${project}#g" ${ASTROSOFTWARE_MENU}
  rm -f ${MERGED_DIR}/*-e

  cp ${STARTUP_DIR}/template/xfce-directory.template ${DIRECTORIES_DIR}/${directory}
  sed -i -e "s#(NAME)#${project}#g" ${DIRECTORIES_DIR}/${directory}
  rm -f ${DIRECTORIES_DIR}/*-e
}

build_menu_item () {
  image_id=$1
  name=$2
  category=$3
  executable="${EXECUTABLE_DIR}/${name}.sh"
  desktop="${DESKTOP_DIR}/${name}.desktop"
  cp ${STARTUP_DIR}/template/software-sh.template $executable
  cp ${STARTUP_DIR}/template/software-category.template $desktop
  sed -i -e "s#(IMAGE_ID)#${image_id}#g" $executable
  sed -i -e "s#(NAME)#${name}#g" $executable
  sed -i -e "s#(NAME)#${name}#g" $desktop
  sed -i -e "s#(HOME)#$HOME#g" $desktop
  sed -i -e "s#(CATEGORY)#${category}#g" $desktop
  rm -f ${EXECUTABLE_DIR}/*-e
  rm -f ${DESKTOP_DIR}/*-e
}

echo "[skaha] Start building menu."
init
create_merged_applications_menu 
apps=$(curl -k -E ~/.ssl/cadcproxy.pem https://${HOST}/skaha/image?type=desktop-app | grep '"id"')
if [[ ${apps} == *"id"* ]]; then
  project_array=()
  while IFS= read -r line
  do
    parts_array=($(echo $line | tr "\"" "\n"))
    if [[ ${#parts_array[@]} -ge 4 && ${parts_array[0]} == "id" ]]; then
      image_id=${parts_array[2]}
      echo "[skaha] image_id: ${image_id}"
      image_array=($(echo ${image_id} | tr "/" "\n"))
      if [[ ${#image_array[@]} -ge 3 ]]; then
        project=${image_array[1]}
        name=${image_array[2]}
	if [[ ! " ${project_array[*]} " =~ " ${project} " ]]; then
          project_array=(${project_array[@]} ${project})
          build_menu ${project} ${name}
        fi
          build_menu_item ${image_id} ${name} ${project}
      fi
    fi
  done < <(printf '%s\n' "$apps")
else
  echo "[skaha] no desktop-app"
fi
complete_merged_applications_menu 
echo "[skaha] Finish building menu."