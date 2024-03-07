#! /bin/bash

git_clone_or_pull() {
  directory="$1"
  absolute_path=${PWD}/${directory}
  git_url="$2"
  branch="$3"
  echo "$absolute_path"
  if [ -d $absolute_path ]; then
    echo "Navigating to directory: $absolute_path"
    cd "$absolute_path"
    git pull -v
    echo "Navigating back to directory: $PWD"
    cd ../
  else
    if [ -n "$branch" ]; then
      git clone "$git_url" -b "$branch"
    else
      git clone "$git_url"
    fi
  fi
}

mkdir -p dependencies && cd dependencies
git_clone_or_pull "group-membership-service" "https://gitlab.com/ska-telescope/src/group-membership-service.git"
# we can not use these three repos directly in the docker-compose context
# git_clone_or_pull "reg" "https://github.com/opencadc/reg.git"
# git_clone_or_pull "iam" "https://github.com/indigo-iam/iam.git"
# git_clone_or_pull "science-platform" "https://github.com/opencadc/science-platform.git" "SP-3544"
cd ../

docker-compose up