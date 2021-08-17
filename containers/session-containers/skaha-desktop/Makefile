
NAME = images.canfar.net/skaha/desktop

build: dependencies Dockerfile
	docker build -t $(NAME):latest -f Dockerfile .

dependencies: clean init software resolutions

software: skaha-software.properties
	./create-software-links.sh

resolutions: skaha-resolutions.properties
	./create-resolution-links.sh

init:
	mkdir -p build
	mkdir -p tmp

.PHONY: clean
clean:
	\rm -rf build
	\rm -rf tmp

