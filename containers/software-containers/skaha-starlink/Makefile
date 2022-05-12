
REPO = images.canfar.net
PROJECT = skaha
DEVNAME = starlink
VERSION = 2021A

NAME = $(REPO)/$(PROJECT)/$(DEVNAME)

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

deploy: build
	docker push $(NAME):$(VERSION)

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
