
VERSIONS = \
	4.7.0-el7 \
	4.7.1-el7 \
	4.7.2-el7 \
	5.0.0-218.el7

#VERSIONS = \
#	5.0.0-218.el7

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run download

all: build

download:
	@- $(foreach V,$(VERSIONS), \
		./download.sh casa-release-$(V); \
	)

build: download
	@- $(foreach V,$(VERSIONS), \
		docker build --build-arg RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$V .; \
    	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)


upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)
