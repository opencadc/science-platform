
VERSIONS = \
	4.7.0-el7 \
	4.7.1-el7 \
	4.7.2-el7 \
	5.0.0-218.el7 \
	5.1.0-74.el7 \
	5.1.1-5.el7 \
	5.1.2-4.el7 \
	5.3.0-143.el7 \
	5.4.0-70.el7

#VERSIONS = \
#	5.4.0-70.el7

LATEST = \
	5.6.1-8.el7

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run download

all: build build-latest

download:
	@- $(foreach V,$(VERSIONS), \
		./download.sh casa-release-$(V); \
	)

download-latest:
	./download.sh casa-pipeline-release-${LATEST} latest; \

build: download
	@- $(foreach V,$(VERSIONS), \
		docker build --build-arg RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$V .; \
	)

build-latest: download-latest
	docker build --build-arg RELEASE=casa-pipeline-release-${LATEST} -t ${DOCKER_REPO_BASE}:${LATEST} .; \

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-latest:
	docker rmi ${DOCKER_REPO_BASE}:$(LATEST)

clean-all: clean clean-latest 

upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-latest: build-latest
	docker push ${DOCKER_REPO_BASE}:$(LATEST)

upload-all: upload upload-latest 
