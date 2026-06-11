#!/bin/bash

HOST=$1

# Callback token
TOKEN="${DESKTOP_SESSION_APP_TOKEN}"

ICON_DIR="/headless/.icons"
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
declare -A app_version

# generate a list of candidate icon names
generate_candidates () {
  current_dir=${PWD}
  cd ${ICON_DIR}
  png_files=`ls *.png`
  svg_files=`ls *.svg`
  non_candidates=()
  for file in ${png_files}
  do
    non_candidates+=(`echo ${file} | cut -f1 -d"."`)
  done
  echo "[skaha] non icon candidates: ${non_candidates[@]}"

  candidates=()
  for file in ${svg_files[@]}
  do
    candidate=(`echo ${file} | cut -f1 -d"."`)
    if [[ ! "${non_candidates[@]}" =~ ${candidate} ]]; then
      candidates+=(${candidate})
    fi
  done
  echo "[skaha] icon candidates: ${candidates[@]}"
  cd ${current_dir}
}

init_app_version () {
  generate_candidates
  for app_name in ${candidates[@]}
  do
    app_version[${app_name}]="${app_name}:"
  done
}

init_skaha_dir () {
  if [[ -d "${SKAHA_DIR}" ]]; then
    # remove the directory
    rm -rf ${SKAHA_DIR}
  fi

  dirs="${EXECUTABLE_DIR} ${DESKTOP_DIR} ${DIRECTORIES_DIR}"
  for dir in ${dirs}; do
    mkdir -p ${dir}
  done
}

init_applications_dir () {
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
}

init () {
  init_app_version
  init_skaha_dir
  init_applications_dir

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
  sed -i -e "s#(PROJECT)#${project}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(DIRECTORY)#${directory}#g" ${ASTROSOFTWARE_MENU}
  sed -i -e "s#(CATEGORY)#${project}#g" ${ASTROSOFTWARE_MENU}
  rm -f ${MERGED_DIR}/*-e

  cp ${STARTUP_DIR}/xfce-directory.template ${DIRECTORIES_DIR}/${directory}
  sed -i -e "s#(NAME)#${project}#g" ${DIRECTORIES_DIR}/${directory}
  rm -f ${DIRECTORIES_DIR}/*-e
}

update_desktop () {
  dest=$1
  short_name=$2
  name=$3
  tmp_file=/tmp/${short_name}.desktop
  cp ${STARTUP_DIR}/app-desktop.template ${tmp_file}
  sed -i -e "s#(VERSION)#${version}#g" ${tmp_file}
  sed -i -e "s#(SHORTNAME)#${short_name}#g" ${tmp_file}
  sed -i -e "s#(NAME)#${name}#g" ${tmp_file}
  sed -i -e "s#(EXECUTABLE)#${EXECUTABLE_DIR}#g" ${tmp_file}
  sed -i -e "s#(CATEGORY)#${category}#g" ${tmp_file}
  cp ${tmp_file} ${dest}
  rm ${tmp_file}
}

build_menu_item () {
  image_id=$1
  name=$2
  category=$3
  tmp_folder="/tmp"
  name_version_array=($(echo $name | tr ":" "\n"))
  short_name=${name_version_array[0]}
  version=${name_version_array[1]}
  executable="${EXECUTABLE_DIR}/${name}.sh"
  tmp_executable="${tmp_folder}/${name}.sh"
  start_executable="${EXECUTABLE_DIR}/start-${name}.sh"
  tmp_start_executable="${tmp_folder}/start-${name}.sh"
  desktop="${DESKTOP_DIR}/${name}.desktop"
  tmp_desktop="${tmp_folder}/${name}.desktop"
  cp ${STARTUP_DIR}/software-sh.template $tmp_executable
  cp ${STARTUP_DIR}/start-software-sh.template ${tmp_start_executable}
  cp ${STARTUP_DIR}/software-category.template $tmp_desktop
  sed -i -e "s#(IMAGE_ID)#${image_id}#g" $tmp_executable
  sed -i -e "s#(TOKEN)#${TOKEN}#g" $tmp_start_executable
  sed -i -e "s#(HOST)#${HOST}#g" $tmp_start_executable
  sed -i -e "s#(SKAHA_API_VERSION)#${SKAHA_API_VERSION}#g" $tmp_start_executable
  sed -i -e "s#(NAME)#${name}#g" $tmp_executable
  sed -i -e "s#(IMAGE_ID)#${image_id}#g" ${tmp_start_executable}
  sed -i -e "s#(NAME)#${name}#g" ${tmp_start_executable}
  sed -i -e "s#(NAME)#${name}#g" $tmp_desktop
  sed -i -e "s#(VERSION)#${version}#g" $tmp_desktop
  sed -i -e "s#(EXECUTABLE)#${EXECUTABLE_DIR}#g" $tmp_desktop
  sed -i -e "s#(CATEGORY)#${category}#g" $tmp_desktop
  chmod +x $tmp_executable
  cp $tmp_executable $executable
  cp $tmp_start_executable ${start_executable}
  cp $tmp_desktop $desktop
  rm -f $tmp_executable
  rm -f $tmp_start_executable
  rm -f $tmp_desktop
  if [[ "${candidates[@]}" =~ (" "|^)${short_name}(" "|$) ]]; then
    if [[ ${image_id} == *"/${category}/${short_name}:"* ]] && [[ "${name}" > "${app_version[${short_name}]}" ]]; then
      # pick the latest version
      app_version[${short_name}]="${name}"
      # accessed via icon on desktop
      update_desktop /headless/Desktop/${short_name}.desktop ${short_name} ${name}
    fi
  fi
  rm -f ${EXECUTABLE_DIR}/*-e
  rm -f ${DESKTOP_DIR}/*-e
}

echo "[skaha] Start building menu."
init
create_merged_applications_menu
curl_out=$(curl -s -k --header "x-auth-token-skaha: ${TOKEN}" "https://${HOST}/skaha/${SKAHA_API_VERSION}/image?type=desktop-app")
if [[ $(echo ${curl_out} | jq '[.[] | .id | length] | add') == 0 ]]; then
  echo "[skaha] no desktop-app"
  echo "${curl_out}"
  exit 1
else
  image_id_list=$(echo ${curl_out} | jq -r '.[] | .id')
  for image_id in ${image_id_list}
  do
    echo "[skaha] image_id: ${image_id}"
    image_array=($(echo ${image_id} | tr "/" "\n"))
    if [[ ${#image_array[@]} -ge 3 ]]; then
      project=${image_array[1]}
      name=${image_array[2]}
      if [[ ! " ${project_array[*]} " =~ " ${project} " ]]; then
        project_array=(${project_array[@]} ${project})
        build_menu ${project} ${name}
      fi

      # Start time in nanoseconds
      start=$(date +%s%N)
      # Execute the build_menu_item function
      build_menu_item ${image_id} ${name} ${project}
      # End time in nanoseconds
      end=$(date +%s%N)

      # Calculate duration in milliseconds using shell arithmetic
      # Note: Bash handles large integers automatically
      duration_ms=$(((end - start) / 1000000))

      echo "Execution time for ${name}: ${duration_ms} ms"
    fi
  done
fi
complete_merged_applications_menu
echo "[skaha] Finish building menu."
