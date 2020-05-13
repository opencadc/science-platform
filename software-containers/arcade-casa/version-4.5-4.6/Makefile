
VERSIONS = \
	4.5.0-el6 \
	4.5.1-el6 \
	4.5.2-el6 \
	4.5.3-el6 \
	4.6.0-el6

#VERSIONS = \
#	4.6.0-el6

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
