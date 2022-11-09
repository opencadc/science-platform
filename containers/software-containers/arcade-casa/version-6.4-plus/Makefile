#NB: before using, uncomment the particular version to be built
#(python 3.8 versions do not work for >CASA6.5 due to a library 
# incompatibility; it did seem okay for 6.4.4, however)
#NB#2: the extract_casaviewer.sh script also needs to be slightly edited
# for each casa version - about halfway down the script, to properly set up 
# plotms, uncomment out the line for that casa version

VERSIONS_PIPELINE = \
#	casa-6.4.1-12-pipeline-2022.2.0.64-py3.6

#!NB casa6.4 and later versions have a different directory naming structure
VERSIONS = \
	casa-6.4.4-31-py3.6
#	casa-6.5.0-15-py3.6
#	casa-6.5.1-23-py3.6
#	casa-6.5.2-26-py3.6

DOCKER_REPO_BASE=images.canfar.net/casa-6/casa

.PHONY: build clean run 

all: build-pipeline build

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

clean-pipeline:
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean:
	@- $(foreach V,$(VERSIONS), \
                docker rmi ${DOCKER_REPO_BASE}:$(V) ; \
	)

clean-all: clean-pipeline clean 

upload-pipeline: build-pipeline
	@- $(foreach V,$(VERSIONS_PIPELINE), \
                docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload: build
	@- $(foreach V,$(VERSIONS), \
                docker push ${DOCKER_REPO_BASE}:$(V) ; \
	)

upload-all: upload-pipeline upload 
