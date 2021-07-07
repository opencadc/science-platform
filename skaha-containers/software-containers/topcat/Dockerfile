FROM fedora:30

# install required software
RUN dnf makecache -y \
    && dnf update -y \
    && dnf install -y java-1.8.0-openjdk.x86_64 ca-certificates sudo which xterm unzip sssd-client acl \
    && dnf clean all \
    && rm -rf /var/cache/yum

# system settings and permissions
COPY src/nofiles.conf /etc/security/limits.d/

## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf

# JVM settings
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -XX:OnError='cat hs_err_pid%p.log'"

# Acquire a numbered TOPCAT release.
# This URL is fairly stable, though not guaranteed in the long term.
ADD http://andromeda.star.bristol.ac.uk/releases/topcat/v4.8/topcat-full.jar /usr/bin/topcat-full.jar
RUN unzip /usr/bin/topcat-full.jar -d /usr/bin topcat stilts
RUN chmod 644 /usr/bin/topcat-full.jar
RUN chmod 755 /usr/bin/topcat /usr/bin/stilts

# Prepare a startup message.
RUN mkdir -p /usr/share/topcat
COPY src/msg.sh /usr/share/topcat
RUN chmod 755 /usr/share/topcat/msg.sh
RUN bash /usr/share/topcat/msg.sh /usr/bin/topcat-full.jar >/usr/share/topcat/msg.txt
RUN echo "cat /usr/share/topcat/msg.txt" >/etc/bash.bashrc

# Check TOPCAT version if applicable.
CMD ["topcat", "-version"]
