# uses python2.6
VERSIONS2.6 = \
	34.0.19988-002-64b \
	40.0.22208-002-64b \
	40.1.22889-003-64b \
	41.0.24668-001-64b-2

# uses python2.7
VERSIONS = \
	42.0.28322-021-1-64b \
	42.1.29047-001-1-64b \
	42.2.30986-1-64b \
	42.2.30986-pipe-1-64b

#VERSIONS2.6 = \
#	34.0.19988-002-64b

#VERSIONS = \
#	42.2.30986-pipe-1-64b

DOCKER_REPO_BASE=images.canfar.net/arcade/casa

.PHONY: build clean run 

all: build2.6 build

build2.6: 
	@- $(foreach V,$(VERSIONS2.6), \
		./download.sh casapy-$(V); \
		docker build --build-arg CASA_RELEASE=casapy-$(V) --build-arg PYTHON=python2.6 -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

build: 
	@- $(foreach V,$(VERSIONS), \
		./download.sh casapy-$(V); \
		docker build --build-arg CASA_RELEASE=casapy-$(V) --build-arg PYTHON=python2.7 -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

clean2.6:
	@- $(foreach V,$(VERSIONS2.6), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)


upload2.6: build2.6
	@- $(foreach V,$(VERSIONS2.6), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean_all: clean2.6 clean
upload_all: upload2.6 upload
