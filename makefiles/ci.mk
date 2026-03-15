.PHONY: check ci vet lint format fmt

vet:
	go vet ./...

lint:
	golangci-lint run

format:
	gofmt -w .

fmt: format

format-check:
	@test -z "$$(gofmt -l .)" || (echo "Files need formatting:" && gofmt -l . && exit 1)

check: vet lint test build

ci: clean check
