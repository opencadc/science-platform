
VERSIONS = \
	4.3.0-el6 \
	4.3.1-el6 \
	4.3.1-pipe-el6 \
	4.4.0-el6 \
	4.5.0-el6 \
	4.5.1-el6 \
	4.5.2-el6 \
	4.5.3-el6 \
	4.6.0-el6 
	4.7.0-el6 \
	4.7.1-el6 \
	4.7.2-el6 \
	5.0.0-218.el6 \
	5.1.0-74.el6 \
	5.1.1-5.el6 \
	5.1.2-4.el6 \
	5.3.0-143.el6 \
	5.4.0-70.el6 

#VERSIONS = \
#	4.3.1-pipe-el6 

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run download

all: build

download:
	@- $(foreach V,$(VERSIONS), \
		./download.sh $(V); \
	)

build: download
	@- $(foreach V,$(VERSIONS), \
		docker build --build-arg VERSION=$(V) -t ${DOCKER_REPO_BASE}:$V .; \
    	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)


upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)
