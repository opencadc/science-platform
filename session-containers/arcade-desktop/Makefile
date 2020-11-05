
NAME = harbor.canfar.net/skaha/session-desktop
VERSION = 0.1

build: dependencies Dockerfile
	docker build -t $(NAME):$(VERSION) -f Dockerfile .

dependencies: clean init software resolutions

software: arcade-software.properties
	./create-software-links.sh

resolutions: arcade-resolutions.properties
	./create-resolution-links.sh

init:
	mkdir -p build
	mkdir -p tmp

.PHONY: clean
clean:
	\rm -rf build
	\rm -rf tmp

