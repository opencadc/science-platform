
NAME = images.canfar.net/carta/skaha-carta
VERSION = 3.0-beta

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: clean

.PHONY: clean
clean:
	\rm -rf build
	\rm -rf tmp

