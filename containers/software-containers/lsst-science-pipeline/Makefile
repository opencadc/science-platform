
REPO = images.canfar.net
PROJECT = lsst
DEVNAME = science_pipeline
VERSION = o_latest

NAME = $(REPO)/$(PROJECT)/$(DEVNAME)

production: dependencies Dockerfile
	docker build --target deploy --build-arg VERSION=$(VERSION) -t $(NAME):$(VERSION) -f Dockerfile .

deploy: production
	docker push $(NAME):$(VERSION)

dev: dependencies Dockerfile
	docker build --target test --build-arg VERSION=$(VERSION) -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
