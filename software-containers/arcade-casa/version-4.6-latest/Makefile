VERSIONS_EL6 = \
	4.6.0-el6

VERSIONS = \
	4.7.0-el7 \
	4.7.1-el7 \
	4.7.2-el7 \
	5.0.0-218.el7 \
	5.1.0-74.el7 \
	5.1.1-5.el7 \
	5.1.2-4.el7 \
	5.3.0-143.el7 \
	5.4.0-70.el7 \
	5.4.1-32.el7 \
	5.4.2-8.el7 \
	5.5.0-149.el7 \
	5.6.0-60.el7

#VERSIONS = \
#	5.4.0-70.el7

LATEST = \
	5.6.1-8.el7

DOCKER_REPO_BASE=bucket.canfar.net/arcade-casa

.PHONY: build clean run 

all: build-el6 build build-latest

build-el6: 
	@- $(foreach V,$(VERSIONS_EL6), \
		./download.sh casa-release-$(V) el6 ; \
		docker build --build-arg RELEASE=casa-release-$(V) --build-arg CENTOS=centos:6 -t ${DOCKER_REPO_BASE}:$V .; \
	)

build: 
	@- $(foreach V,$(VERSIONS), \
		./download.sh casa-release-$(V) el7 ; \
		docker build --build-arg RELEASE=casa-release-$(V) --build-arg CENTOS=centos:7 -t ${DOCKER_REPO_BASE}:$V .; \
	)

build-latest: 
	./download.sh casa-pipeline-release-${LATEST} latest ; \
	docker build --build-arg RELEASE=casa-pipeline-release-${LATEST} --build-arg CENTOS=centos:7 -t ${DOCKER_REPO_BASE}:${LATEST} .; \

clean-el6:
	@- $(foreach V,$(VERSIONS_EL6), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
	  docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-latest:
	docker rmi ${DOCKER_REPO_BASE}:$(LATEST)

clean-all: clean-el6 clean clean-latest 

upload-el6: build-el6
	@- $(foreach V,$(VERSIONS_EL6), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload: build
	@- $(foreach V,$(VERSIONS), \
	  docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-latest: build-latest
	docker push ${DOCKER_REPO_BASE}:$(LATEST)

upload-all: upload-el6 upload upload-latest 
