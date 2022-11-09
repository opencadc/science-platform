VERSIONS_OLD_4 = \
	4.5.0-el6 \
	4.5.1-el6 \
	4.5.2-el6 \
	4.5.3-el6 \
	4.6.0-el6 \
	4.7.0-el6 \
	4.7.1-el6 \
	4.7.2-el6
VERSIONS_OLD_5 = \
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


VERSIONS_PIPELINE = \
#	5.6.1-8.el6
#	5.6.3-19.el6

VERSIONS = \
#	5.7.0-134.el6 \
#	5.7.2-4.el6 \
#	5.8.0-109.el6 

DOCKER_REPO_BASE_4=images.canfar.net/casa-4/casa
DOCKER_REPO_BASE=images.canfar.net/casa-5/casa

.PHONY: build clean run 

all: build-old-4 build-old-5 build-pipeline build
build-old-4: 
	@- $(foreach V,$(VERSIONS_OLD_4), \
		./download.sh casa-release-$(V) old-4 ; \
		docker build --build-arg CASA_RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE_4}:$(V) .; \
	)
build-old-5: 
	@- $(foreach V,$(VERSIONS_OLD_5), \
		./download.sh casa-release-$(V) old-5 ; \
		docker build --build-arg CASA_RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)


build-pipeline: 
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                ./download.sh casa-pipeline-release-$(V) pipeline ; \
                docker build --build-arg CASA_RELEASE=casa-pipeline-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

build: 
	@- $(foreach V,$(VERSIONS), \
                ./download.sh casa-release-$(V) current ; \
                docker build --build-arg CASA_RELEASE=casa-release-$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

clean-old-4:
	@- $(foreach V,$(VERSIONS_OLD_4), \
		docker rmi ${DOCKER_REPO_BASE_4}:$(V) ; \
	)

clean-old-5:
	@- $(foreach V,$(VERSIONS_OLD_5), \
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

clean-all: clean-old-4 clean-old-5 clean-pipeline clean 

upload-old-4: build-old-4
	@- $(foreach V,$(VERSIONS_OLD_4), \
	  docker push ${DOCKER_REPO_BASE_4}:$(V) ; \
	)
upload-old-5: build-old-5
	@- $(foreach V,$(VERSIONS_OLD_5), \
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

upload-all: upload-old-4 upload-old-5 upload-pipeline upload 