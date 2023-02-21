FROM codercom/code-server

#FROM gitpod/openvscode-server:latest

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

USER root
RUN mkdir /skaha
ADD src/startup.sh /skaha/
RUN chmod gou+x /skaha/startup.sh

USER coder
EXPOSE 5000

ENTRYPOINT [ "/skaha/startup.sh" ]
