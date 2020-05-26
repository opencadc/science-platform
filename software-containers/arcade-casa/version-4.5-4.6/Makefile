VERSIONS_PATCH = \
	4.5.0-el6 \
	4.5.1-el6 \
	4.5.2-el6 \
	4.5.3-el6

VERSIONS = \
	4.6.0-el6

#VERSIONS_PATCH = \
#	4.5.1-el6

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run 

all: build build-patch

build-patch: 
	@- $(foreach V,$(VERSIONS_PATCH), \
		./download.sh $(V); \
		docker build --build-arg RELEASE=casa-release-$(V) --build-arg STARTUP=startup-patch.sh -t ${DOCKER_REPO_BASE}:$V .; \
   	)

build: 
	@- $(foreach V,$(VERSIONS), \
		./download.sh $(V); \
		docker build --build-arg RELEASE=casa-release-$(V) --build-arg STARTUP=startup.sh -t ${DOCKER_REPO_BASE}:$V .; \
   	)

clean-patch:
	@- $(foreach V,$(VERSIONS_PATCH), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-all: clean clean-patch

upload-patch: build-patch 
	@- $(foreach V,$(VERSIONS_PATCH), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)
upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-all: upload upload-patch
