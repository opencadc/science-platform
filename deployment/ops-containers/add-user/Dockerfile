FROM fedora:30 

# system settings and permissions
COPY src/add-user /usr/bin
RUN mkdir /root/.ssl

CMD ["/usr/bin/add-user"]

