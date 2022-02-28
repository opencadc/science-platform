
DEVNAME = terminal

NAME = harbor.canfar.net.canfar.net/skaha/$(DEVNAME)
VERSION = 0.1

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
