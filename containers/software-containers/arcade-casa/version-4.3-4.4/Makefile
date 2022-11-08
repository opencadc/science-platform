
VERSIONS = \
	4.3.0-el6 \
#	4.3.1-el6 \
	4.3.1-pipe-el6 \
	4.4.0-el6 

#VERSIONS = \
#	4.4.0-el6 

DOCKER_REPO_BASE=images.canfar.net/casa-4/casa

.PHONY: build clean run 

all: build

build: 
	@- $(foreach V,$(VERSIONS), \
		./download.sh $(V); \
		docker build --build-arg CASA_RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
    	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)


upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)
