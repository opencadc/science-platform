
NAME = images.canfar.net/carta/skaha-carta
VERSION = 2.0

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: clean

.PHONY: clean
clean:
	\rm -rf build
	\rm -rf tmp

