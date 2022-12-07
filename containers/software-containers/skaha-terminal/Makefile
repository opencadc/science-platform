
DEVNAME = terminal

NAME = images.canfar.net/skaha/$(DEVNAME)
VERSION = 1.1.2

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
