FROM fedora:30 

# add often used tools
RUN dnf -y install which

# needed to get/set quota
RUN dnf -y install attr

# system settings and permissions
COPY src/add-user /usr/bin
RUN mkdir /root/.ssl

CMD ["/usr/bin/add-user"]

