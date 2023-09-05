#!/bin/bash

# One argument expected: the skaha session ID, which makes up part of the connect url

# HOME is defined at runtime.
cd ${HOME}

echo $2 >> /etc/passwd

# adding all the groups
DELIMITER=";"
GROUP_ENTRIES=($(awk -F"$DELIMITER" '{for(i=1; i<=NF; i++) print $i}' <<< "$3"))
for GROUP_ENTRY in "${GROUP_ENTRIES[@]}"; do
  	echo $GROUP_ENTRY >> /etc/group
done

/usr/sbin/pwconv
export USER_NAME=$(echo $2| cut -d":" -f1)


su - $USER_NAME -c "/opt/conda/bin/jupyter lab \
	--NotebookApp.base_url=session/notebook/\"$1\" \
	--NotebookApp.notebook_dir=/ \
	--NotebookApp.allow_origin=\"*\" \
	--ServerApp.ip=0.0.0.0 \
	--ServerApp.port=8888 \
	--no-browser \
	--ServerApp.base_url=session/notebook/\"$1\" \
	--ServerApp.root_dir=/ \
	--ServerApp.allow_origin=\"*\" \
	${JUPYTERLAB_ARGS}"
