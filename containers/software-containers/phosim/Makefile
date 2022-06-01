
DEVNAME = phosim

NAME = images.canfar.net/skaha/$(DEVNAME)
VERSION = 5.6.11

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) --build-arg PHOSIM_VERSION=$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
