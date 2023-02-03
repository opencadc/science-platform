FROM ubuntu:latest

RUN  apt update \
  && apt install -y wget build-essential \
  && rm -rf /var/lib/apt/lists/*



# install the global Julia packages in /opt/julia instead of $HOME
ENV JULIA_DEPOT_PATH=/opt/julia
ENV JULIA_PKGDIR=/opt/julia
RUN mkdir "${JULIA_PKGDIR}" && mkdir "${JULIA_PKGDIR}/artifacts"

ENV PATH="/julia-1.9.0-beta3/bin:${PATH}"
RUN echo "PATH: ${PATH}"

# Copy a script that we will use to correct permissions after running certain commands
COPY fix-permissions /usr/local/bin/fix-permissions
RUN chmod a+rx /usr/local/bin/fix-permissions


WORKDIR /
RUN wget https://julialang-s3.julialang.org/bin/linux/x64/1.9/julia-1.9.0-beta3-linux-x86_64.tar.gz && \
    tar -xvf julia-1.9.0-beta3-linux-x86_64.tar.gz && \
    rm julia-1.9.0-beta3-linux-x86_64.tar.gz

# Install Pluto etc.

# This could be done more carefully by copying in a Julia Project.toml and/or Manifest.toml
# to ensure reproducibility. Here, we just get the latest versions of each.
RUN julia -e 'using Pkg; Pkg.add("Pluto")' && \
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
ENTRYPOINT [ "/skaha/startup.sh" ]
