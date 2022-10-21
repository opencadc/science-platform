# NB: before using, uncomment the version to be built
# NB2: One manual edit is also required in the extract_casaviewer.sh script,
# about halfway down, to again select the command associated with setting up
# plotms for the desired version of CASA

VERSIONS_OLD = \
# 	casa-6.1.0-118# \
# 	casa-6.2.0-124# \
# 	casa-6.3.0-48 #\

VERSIONS_PIPELINE = \
	casa-6.4.1-12-pipeline-2022.2.0.64-py3.6
# 	casa-6.2.1-7-pipeline-2021.2.0.128
#	casa-6.1.1-15-pipeline-2020.1.0.40

#!NB >casa6.4.3 versions have a different directory naming structure
VERSIONS = \
#	casa-6.4.0-16-py3.6
#	casa-6.4.3-27-py3.6

DOCKER_REPO_BASE=images.canfar.net/casa-6/casa

.PHONY: build clean run 

all: build-old build-pipeline build

build-old: 
	@- $(foreach V,$(VERSIONS_OLD), \
		./download.sh $(V) old ; \
		docker build --build-arg CASA_RELEASE=$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

build-pipeline: 
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                ./download.sh $(V) pipeline ; \
                docker build --build-arg CASA_RELEASE=$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
	)

build: 
	@- $(foreach V,$(VERSIONS), \
                ./download.sh $(V) current ; \
                docker build --build-arg CASA_RELEASE=$(V) -t ${DOCKER_REPO_BASE}:$(V) .; \
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
