FROM centos:7

RUN yum makecache -y
RUN yum update -y
RUN yum install -y iproute lsof sssd-client acl wget

RUN cd /
RUN wget https://julialang-s3.julialang.org/bin/linux/x64/1.7/julia-1.7.2-linux-x86_64.tar.gz
RUN tar zxvf julia-1.7.2-linux-x86_64.tar.gz
ENV PATH="/julia-1.7.2/bin:${PATH}"
RUN echo "PATH: ${PATH}"

RUN mkdir /skaha
ADD src/startup.sh /skaha/
RUN chmod u+x /skaha/startup.sh

# pre-install pluto
RUN mkdir /.julia
RUN chmod 777 /.julia
RUN julia -e 'using Pkg; Pkg.add("Pluto")'
EXPOSE 1234

# nsswitch for correct sss lookup
ADD src/nsswitch.conf /etc/

CMD ["julia"]
