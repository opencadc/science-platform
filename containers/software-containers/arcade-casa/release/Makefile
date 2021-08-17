VERSIONS = \
	34.0.19988-002-64b \
	40.0.22208-002-64b \
	40.1.22889-003-64b \
	41.0.24668-001-64b-2
	42.0.28322-021-1-64b \
	42.1.29047-001-1-64b \
	42.2.30986-1-64b \
	42.2.30986-pipe-1-64b \
	4.3.0-el6 \
	4.3.1-el6 \
	4.3.1-pipe-el6 \
	4.4.0-el6 \
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
	5.6.0-60.el6 \
	5.6.1-8.el6

#VERSIONS = \
#	42.2.30986-pipe-1-64b

DOCKER_REPO_SOURCE=bucket.canfar.net/arcade-casa
DOCKER_REPO_DEST=canfar-registry.cloud.computecanada.ca/arcade-casa

.PHONY: build clean run 

all: build

build: 
	@- $(foreach V,$(VERSIONS), \
		./pull.sh ${DOCKER_REPO_SOURCE} $(V); \
		./push.sh ${DOCKER_REPO_DEST} $(V); \
	)

pull: 
	@- $(foreach V,$(VERSIONS), \
		./pull.sh ${DOCKER_REPO_SOURCE} $(V); \
	)

push: 
	@- $(foreach V,$(VERSIONS), \
		./push.sh ${DOCKER_REPO_DEST} $(V); \
	)


clean:
	@- $(foreach V,$(VERSIONS), \
		./clean.sh $(V); \
	)

