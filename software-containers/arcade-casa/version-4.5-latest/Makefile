VERSIONS_OLD = \
	4.5.0-el6 \
	4.5.1-el6 \
	4.5.2-el6 \
	4.5.3-el6 \
	4.6.0-el6 \
	4.7.0-el6 \
	4.7.1-el6 \
	4.7.2-el6 \
	5.0.0-218.el6 \
	5.1.0-74.el6 \
	5.1.1-5.el6 \
	5.1.2-4.el6 \
	5.3.0-143.el6 \
	5.4.0-70.el6 \
	5.4.1-32.el6 \
	5.4.2-8.el6 \
	5.5.0-149.el6 \
	5.6.0-60.el6

#VERSIONS_OLD = \
#	4.5.0-el6

VERSIONS_PIPELINE = \
	5.6.1-8.el6

VERSIONS = \
	5.7.0-134.el6

DOCKER_REPO_BASE=harbor.canfar.net/arcade/casa

.PHONY: build clean run 

all: build-old build-pipeline build

build-old: 
	@- $(foreach V,$(VERSIONS_OLD), \
		./download.sh casa-release-$(V) old ; \
		docker build --build-arg RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$V .; \
	)

build-pipeline: 
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                ./download.sh casa-pipeline-release-$(V) pipeline ; \
                docker build --build-arg RELEASE=casa-pipeline-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

build: 
	@- $(foreach V,$(VERSIONS), \
                ./download.sh casa-release-$(V) current ; \
                docker build --build-arg RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

clean-old:
	@- $(foreach V,$(VERSIONS_OLD), \
		docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-pipeline:
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
                docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-all: clean-old clean-pipeline clean 

upload-old: build-old
	@- $(foreach V,$(VERSIONS_OLD), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-pipeline: build-pipeline
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload: build
	@- $(foreach V,$(VERSIONS), \
                docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-all: upload-old upload-pipeline upload 
