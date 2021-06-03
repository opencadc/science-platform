FROM fedora:30

# install required software
RUN dnf makecache -y \
    && dnf update -y \
    && dnf install -y java-1.8.0-openjdk.x86_64 ca-certificates sudo which xterm unzip sssd-client acl \
    && dnf clean all \
    && rm -rf /var/cache/yum

# system settings and permissions
COPY src/nofiles.conf /etc/security/limits.d/

# startup file, just writes to the console
COPY src/init.sh /skaha/

## see https://bugzilla.redhat.com/show_bug.cgi?id=1773148
RUN touch /etc/sudo.conf && echo "Set disable_coredump false" > /etc/sudo.conf

# JVM settings
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -XX:OnError='cat hs_err_pid%p.log'"

# This grabs the most recent TOPCAT/STILTS release.
# For fixed versions you could use ftp://andromeda.star.bris.ac.uk/pub/star/topcat/vX.X/
ADD http://www.starlink.ac.uk/topcat/topcat-full.jar /usr/bin/topcat-full.jar
RUN unzip /usr/bin/topcat-full.jar -d /usr/bin topcat stilts
RUN chmod 644 /usr/bin/topcat-full.jar
RUN chmod 755 /usr/bin/topcat /usr/bin/stilts

# Check STILTS version if applicable.
CMD ["/skaha/init.sh"]
