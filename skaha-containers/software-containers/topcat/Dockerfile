FROM fedora:30

# install required software
RUN dnf makecache -y \
    && dnf update -y \
    && dnf install -y java-1.8.0-openjdk.x86_64 ca-certificates sudo which xterm sssd-client acl \
    && dnf clean all \
    && rm -rf /var/cache/yum

# system settings and permissions
COPY src/nofiles.conf /etc/security/limits.d/

## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf

# JVM settings
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -XX:OnError='cat hs_err_pid%p.log'"

ADD http://www.starlink.ac.uk/topcat/topcat-full.jar /usr/local/bin/topcat-full.jar
RUN chmod 755 /usr/local/bin/topcat-full.jar
CMD ["java", "-jar", "/usr/local/bin/topcat-full.jar"]
