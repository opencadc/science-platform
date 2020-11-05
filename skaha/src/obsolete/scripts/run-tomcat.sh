#!/bin/bash
#This doesn't produce the external IP in openstack.
#HOST_IP=`hostname --ip-address`
HOST_IP=206.12.59.46
#HOSTNAME=`hostname`
HOSTNAME=${ARCADE_HOST}.canfar.net
docker run -d --rm -it --net=arcade-net --name=arcade-tomcat --hostname=arcade-tomcat --env docker.hostip=$HOST_IP --env arcade.hostname=$HOSTNAME --env arcade.homedir=${ARCADE_HOME_DIR} --env arcade.scratchdir=${ARCADE_SCRATCH_DIR} --env HOME=/root -v ${ARCADE_LOG_DIR}/tomcat:/usr/local/tomcat/logs -v /home/servops/.ssl:/root/.ssl -v ${HOME}/config:/root/config -v ${ARCADE_SCRIPTS_DIR}:/scripts -v ${ARCADE_HOME_DIR}:/home -v /var/run/docker.sock:/var/run/docker.sock bucket.canfar.net/arcade-tomcat:0.2
