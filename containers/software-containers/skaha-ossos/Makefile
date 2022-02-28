
DEVNAME = skaha-ossos

NAME = bucket.canfar.net/$(DEVNAME)
VERSION = latest

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
