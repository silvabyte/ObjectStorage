.PHONY: check ci vet lint format fmt

vet:
	go vet ./...

lint:
	go run github.com/golangci/golangci-lint/cmd/golangci-lint@latest run

format:
	gofmt -w .

fmt: format

format-check:
	@test -z "$$(gofmt -l .)" || (echo "Files need formatting:" && gofmt -l . && exit 1)

check: vet lint test build

ci: clean check
