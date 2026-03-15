.PHONY: test test-only test-cover

test:
	go test ./...

# Run specific test: make test-only T=TestUpload
test-only:
	go test -run $(T) ./...

test-cover:
	go test -coverprofile=coverage.out ./... && go tool cover -html=coverage.out
