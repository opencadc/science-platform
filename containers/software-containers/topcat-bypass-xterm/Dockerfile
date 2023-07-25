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

# install topcat with parquet and create stilts/topcat scripts
ADD https://www.star.bris.ac.uk/~mbt/topcat/topcat-extra.jar /usr/local/bin/topcat-extra.jar
RUN unzip -o /usr/local/bin/topcat-extra.jar -d /usr/local/bin topcat stilts \
    && sed -i -e 's/topcat-full/topcat-extra/g' /usr/local/bin/topcat /usr/local/bin/stilts \
    && chmod 644 /usr/local/bin/topcat-extra.jar \
    && chmod 755 /usr/local/bin/stilts /usr/local/bin/topcat

# Acquire a numbered TOPCAT release.
# This URL is fairly stable, though not guaranteed in the long term.
#ADD http://andromeda.star.bristol.ac.uk/releases/topcat/v4.8/topcat-full.jar /usr/bin/topcat-full.jar
# Above URL was not working. The following URL was providing topcat version 4.8.8
# Need to find a version specific URL.
ADD https://www.star.bris.ac.uk/~mbt/topcat/topcat-full.jar /usr/bin/topcat-full.jar
RUN unzip /usr/bin/topcat-full.jar -d /usr/bin topcat stilts
RUN chmod 644 /usr/bin/topcat-full.jar
RUN chmod 755 /usr/bin/topcat /usr/bin/stilts

# Prepare a startup message.
RUN mkdir -p /usr/share/topcat
COPY src/msg.sh /usr/share/topcat
RUN chmod 755 /usr/share/topcat/msg.sh
RUN bash /usr/share/topcat/msg.sh /usr/bin/topcat-full.jar >/usr/share/topcat/msg.txt
RUN echo "cat /usr/share/topcat/msg.txt" >/etc/bash.bashrc

# start up topcat directly, bypassing the xterm
RUN mkdir /skaha
COPY src/startup.sh /skaha/startup.sh
RUN chmod +x /skaha/startup.sh

