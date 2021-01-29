FROM jupyter/scipy-notebook:latest

USER root

RUN apt-get -y update
RUN apt-get -y install xorg xterm gcc sudo vim
RUN apt-get -y install sssd acl

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

#RUN chmod -R 777 /home/$NB_USER

# No more built-in startup script
#ADD src/skaha-start-notebook /home/jovyan/
#RUN chmod +x /home/jovyan/skaha-start-notebook 
#CMD ["/home/jovyan/skaha-start-notebook"]
