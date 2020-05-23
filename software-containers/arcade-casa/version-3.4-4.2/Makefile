VERSIONS = \
	34.0.19988-002-64b \
	40.0.22208-002-64b \
	40.1.22889-003-64b \
	41.0.24668-001-64b \
	42.0.28322-021-1-64b \
	42.1.29047-001-1-64b \
	42.2.30986-1-64b

#VERSIONS = \
#	42.2.30986-1-64b

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run download

all: build

download:
	@- $(foreach V,$(VERSIONS), \
		./download.sh casapy-$(V); \
	)

build: download
	@- $(foreach V,$(VERSIONS), \
		docker build --build-arg RELEASE=casapy-$(V) -t ${DOCKER_REPO_BASE}:$V .; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)


upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)
