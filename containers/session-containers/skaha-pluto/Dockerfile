FROM centos:7

RUN yum makecache -y && \
    yum update -y && \
    yum install -y iproute lsof sssd-client acl wget && yum group install -y "Development Tools" && \
    yum clean all

WORKDIR /
RUN wget https://julialang-s3.julialang.org/bin/linux/x64/1.9/julia-1.9.0-rc2-linux-x86_64.tar.gz && \
    tar zxvf julia-1.9.0-rc2-linux-x86_64.tar.gz && \
    rm julia-1.9.0-rc2-linux-x86_64.tar.gz

# We want to set up Julia carefully.
# We want some packages e.g. Pluto backed into the container,
# and we want others to persist in the user's home folder.
# We also want JULIA_PROJECT set up so that by default they
# get the stack of their home packages ontop of the image packages.
# Finally, we need to load the registry while making this image
# but then need to clear it so that the user re-loads it into their 
# home folder (otherwise all package versions are frozen at the time
# this image is created).

# Pluto by default does track all notebook dependencies internally,
# but we want to fall back on best-practices if the user opts
# out of the default automatic handling.

# install the global Julia packages in /opt/julia instead of $HOME
ENV JULIA_DEPOT_PATH=/opt/julia
ENV JULIA_PKGDIR=/opt/julia
RUN mkdir "${JULIA_PKGDIR}" && mkdir "${JULIA_PKGDIR}/artifacts"

ENV PATH="/julia-1.9.0-rc2/bin:${PATH}"
RUN echo "PATH: ${PATH}"

# Copy a script that we will use to correct permissions after running certain commands
COPY fix-permissions /usr/local/bin/fix-permissions
RUN chmod a+rx /usr/local/bin/fix-permissions



# Install Pluto etc.

# This could be done more carefully by copying in a Julia Project.toml and/or Manifest.toml
# to ensure reproducibility. Here, we just get the latest versions of each.
# Once complete, create a custom "sys-image" to compile Pluto into the Julia binary.
# Then it will start almost instantly.
# We do this all in one step to avoid inflating the image size with temporary steps.

# One cautionary note about this approach: since these packages are now baked into the Julia
# binary (through PlutoSysImg.so) users can't download and load other versions. This is fine
# for e.g. Pluto but might cause issues if they install a new package that is incompatible with
# a transitive dependency like requiring an old version of HTML.jl
RUN julia -e 'using Pkg; Pkg.add(["Pluto"]);' && \
    rm -rf ${JULIA_DEPOT_PATH}/registries/General && \
    chmod -R 755 ${JULIA_DEPOT_PATH}/artifacts && \
    fix-permissions /julia* /opt/julia


# These variables set up new packages to go into the user's persistent .julia directory, and keep the project/manifest in their home directory by default, while retaining access to the packages we pre-bake into the container
# Basically, the user will have full control & reproducibility of their packages, with the exception of the above pre-baked / compiled ones
RUN rm -rf ${JULIA_DEPOT_PATH}/registries/General && chmod -R 755 ${JULIA_DEPOT_PATH}/artifacts

# New pacakges get installed into here:
ENV JULIA_DEPOT_PATH="~/.julia:$JULIA_DEPOT_PATH"
# Packages can get loaded from both depots
ENV JULIA_LOAD_PATH="/opt/julia/environments/v1.9/Project.toml:"
# Default project environment kept in the user's home directory
ENV JULIA_PROJECT="~"

RUN mkdir /skaha
ADD src/startup.sh /skaha/
RUN chmod gou+x /skaha/startup.sh

EXPOSE 5000

# Network file systems do not fire file events without polling
ENV JULIA_REVISE_POLL=1

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

# For local testing of permissions
# RUN useradd -ms /bin/bash tstusr
# WORKDIR /home/newuser
# USER tstusr
ENTRYPOINT [ "/skaha/startup.sh" ]
