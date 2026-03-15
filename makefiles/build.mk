.PHONY: build run clean

build:
	go build -o bin/objectstorage ./cmd/objectstorage

run:
	go run ./cmd/objectstorage

clean:
	rm -rf bin/ coverage.out
