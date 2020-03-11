
DEVNAME = arcade-starlink

NAME = bucket.canfar.net/$(DEVNAME)
VERSION = 0.3

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: 

init:
	mkdir -p build

.PHONY: clean
clean:
	\rm -rf build
