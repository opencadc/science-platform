#!/bin/bash

HOST=$1
STARTUP_DIR="/desktopstartup"
SKAHA_DIR="$HOME/.local/skaha"
EXECUTABLE_DIR="$HOME/.local/skaha/bin"
XFCE_DESKTOP_DIR_PARENT="$HOME/.local/share"
XFCE_DESKTOP_DIR="${XFCE_DESKTOP_DIR_PARENT}/applications"
DESKTOP_DIR="$HOME/.local/skaha/share/applications"
DIRECTORIES_DIR="$HOME/.local/skaha/share/desktop-directories"
START_ASTROSOFTWARE_MENU="${STARTUP_DIR}/astrosoftware-top.menu"
END_ASTROSOFTWARE_MENU="${STARTUP_DIR}/astrosoftware-bottom.menu"
MERGED_DIR="/etc/xdg/menus/applications-merged"
ASTROSOFTWARE_MENU="${MERGED_DIR}/astrosoftware.menu"
ds9_version="ds9:"
ds9_terminal_version="ds9-terminal:"
terminal_version="terminal:"
topcat_version="topcat:"
topcat_terminal_version="topcat-terminal:"

init_dir () {
  if [[ -d "$1" ]]; then
    # empty the directory
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

  # XFCE is hardcoded to use ~/.local/share/applications
  if [[ -d ${XFCE_DESKTOP_DIR} ]]; then
    # directory already exists, delete it
    rm -rf ${XFCE_DESKTOP_DIR}
  fi

  # create soft link if there isn't one already
  # ensure the parent directory is created first
  if [[ ! -L ${XFCE_DESKTOP_DIR} ]]; then
    mkdir -p ${XFCE_DESKTOP_DIR_PARENT}
    ln -s ${DESKTOP_DIR} ${XFCE_DESKTOP_DIR}
  fi

  # sleep-forever.sh is used on desktop-app start up, refer to start-software-sh.template
  cp /skaha-system/sleep-forever.sh ${EXECUTABLE_DIR}/.
}

build_resolution_items () {
  RESOLUTION_SH="${STARTUP_DIR}/resolution-sh.template"
  RESOLUTION_DESKTOP="${STARTUP_DIR}/resolution-desktop.template"
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
        sed -i -e "s#(EXECUTABLE)#${EXECUTABLE_DIR}#g" ${desktop}
        rm -f ${DEKSTOP_DIR}/*-e
      done < ${STARTUP_DIR}/skaha-resolutions.properties
    else
      echo "[skaha] ${RESOLUTION_DESKTOP} does not exist"
      exit 1
    fi
  else
    echo "[skaha] ${RESOLUTION_SH} does not exist"
    exit 1
  fi
}

build_resolution_menu () {
  RESOLUTION="resolution"
  cp ${STARTUP_DIR}/xfce-directory.template ${DIRECTORIES_DIR}/xfce-${RESOLUTION}.directory
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
    cp ${STARTUP_DIR}/xfce-directory.template ${DIRECTORIES_DIR}/xfce-canfar.directory
    sed -i -e "s#(NAME)#AstroSoftware#g" ${DIRECTORIES_DIR}/xfce-canfar.directory
    rm -f ${DIRECTORIES_DIR}/*-e
    cp ${DIRECTORIES_DIR}/xfce-canfar.directory ${DIRECTORIES_DIR}/canfar.directory
  else
    echo "[skaha] ${START_ASTROSOFTWARE_MENU} does not exist"
    exit 1
  fi
}

complete_merged_applications_menu () {
  if [[ -f "${ASTROSOFTWARE_MENU}" ]]; then
    cat ${END_ASTROSOFTWARE_MENU} >> ${ASTROSOFTWARE_MENU}
    build_resolution_menu
  else
    echo "[skaha] ${ASTROSOFTWARE_MENU} does not exist"
    exit 1
  fi
}

build_menu () {
  project=$1
  directory="xfce-$1.directory"
  cat ${STARTUP_DIR}/xfce-applications-menu-item.template >> ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(NAME)#${project}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(DIRECTORY)#${directory}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(CATEGORY)#${project}#g" ${ASTROSOFTWARE_MENU}
  rm -f ${MERGED_DIR}/*-e

  cp ${STARTUP_DIR}/xfce-directory.template ${DIRECTORIES_DIR}/${directory}
  sed -i -e "s#(NAME)#${project}#g" ${DIRECTORIES_DIR}/${directory}
  rm -f ${DIRECTORIES_DIR}/*-e
}

update_desktop () {
  script_name="${EXECUTABLE_DIR}/$2.sh"
  cp ${STARTUP_DIR}/$1.desktop.template /tmp/$1.desktop
  sed -i -e "s#(SCRIPT)#${script_name}#g" /tmp/$1.desktop
  cp /tmp/$1.desktop /headless/Desktop/$1.desktop
  rm /tmp/$1.desktop
}

update_terminal_desktop () {
  script_name="${EXECUTABLE_DIR}/$2.sh"
  cp ${STARTUP_DIR}/terminal.desktop.template /tmp/terminal.desktop
  sed -i -e "s#(SCRIPT)#${script_name}#g" /tmp/terminal.desktop
  cp /tmp/terminal.desktop $1
  rm /tmp/terminal.desktop
}

build_menu_item () {
  image_id=$1
  name=$2
  category=$3
  executable="${EXECUTABLE_DIR}/${name}.sh"
  start_executable="${EXECUTABLE_DIR}/start-${name}.sh"
  desktop="${DESKTOP_DIR}/${name}.desktop"
  cp ${STARTUP_DIR}/software-sh.template $executable
  cp ${STARTUP_DIR}/start-software-sh.template ${start_executable}
  cp ${STARTUP_DIR}/software-category.template $desktop
  sed -i -e "s#(IMAGE_ID)#${image_id}#g" $executable
  sed -i -e "s#(NAME)#${name}#g" $executable
  sed -i -e "s#(IMAGE_ID)#${image_id}#g" ${start_executable}
  sed -i -e "s#(NAME)#${name}#g" ${start_executable}
  sed -i -e "s#(NAME)#${name}#g" $desktop
  sed -i -e "s#(EXECUTABLE)#${EXECUTABLE_DIR}#g" $desktop
  sed -i -e "s#(CATEGORY)#${category}#g" $desktop
  if [[ ${image_id} == *"/skaha/ds9"* ]]; then
    name_version_array=($(echo $name | tr ":" "\n"))
    short_name=${name_version_array[0]}
    # ds9 desktop accessed via ds9 icon on desktop
    if [[ ${name} == *"${ds9_version}"* && "${name}" > "${ds9_version}" ]]; then
      ds9_version=${name}
      update_desktop ${short_name} ${name}
    elif [[ ${name} == *"${ds9_terminal_version}"* && "${name}" > "${ds9_terminal_version}" ]]; then
      ds9_terminal_version=${name}
      update_desktop ${short_name} ${name}
    fi
  fi
  if [[ ${image_id} == *"/skaha/terminal:"* ]] && [[ "${name}" > "${terminal_version}" ]]; then
    terminal_version=${name}
    # terminal.desktop accessed via "Applications->terminal"
    update_terminal_desktop /usr/share/applications/terminal.desktop ${name}
    # terminal.desktop accessed via terminal icon on desktop
    update_terminal_desktop /headless/Desktop/terminal.desktop ${name}
  fi
  if [[ ${image_id} == *"/skaha/topcat"* ]]; then
    name_version_array=($(echo $name | tr ":" "\n"))
    short_name=${name_version_array[0]}
    # topcat desktop accessed via topcat icon on desktop
    if [[ ${name} == *"${topcat_version}"* && "${name}" > "${topcat_version}" ]]; then
      topcat_version=${name}
      update_desktop ${short_name} ${name}
    elif [[ ${name} == *"${topcat_terminal_version}"* && "${name}" > "${topcat_terminal_version}" ]]; then
      topcat_terminal_version=${name}
      update_desktop ${short_name} ${name}
    fi
  fi
  rm -f ${EXECUTABLE_DIR}/*-e
  rm -f ${DESKTOP_DIR}/*-e
}

echo "[skaha] Start building menu."
init
create_merged_applications_menu
apps=$(curl -s -k -E ~/.ssl/cadcproxy.pem https://${HOST}/skaha/v0/image?type=desktop-app | grep '"id"')
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
  exit 1
fi
complete_merged_applications_menu
echo "[skaha] Finish building menu."
