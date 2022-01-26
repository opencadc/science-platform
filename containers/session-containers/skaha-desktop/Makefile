
NAME = images.canfar.net/skaha/desktop

build: dependencies Dockerfile
	docker build -t $(NAME):latest -f Dockerfile .

dependencies: clean init

init:
	mkdir -p build
	mkdir -p tmp

.PHONY: clean
clean:
	\rm -rf build
	\rm -rf tmp

