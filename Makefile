# ObjectStorage Makefile
# Modular build system for Go project

.PHONY: help
help:
	@echo "ObjectStorage Build System"
	@echo ""
	@echo "Build & Run:"
	@echo "  make build        - Compile the project"
	@echo "  make run          - Run the ObjectStorage server"
	@echo ""
	@echo "Format & Lint:"
	@echo "  make format       - Format code with gofmt"
	@echo "  make fmt          - Alias for format"
	@echo "  make format-check - Check formatting without changes"
	@echo "  make vet          - Run go vet"
	@echo "  make lint         - Run golangci-lint"
	@echo ""
	@echo "Testing:"
	@echo "  make test         - Run all tests"
	@echo "  make test-only T= - Run specific test (e.g., make test-only T=TestUpload)"
	@echo "  make test-cover   - Run tests with coverage report"
	@echo ""
	@echo "CI:"
	@echo "  make check        - Run vet + lint + test + build"
	@echo "  make ci           - Clean build + full check"
	@echo "  make clean        - Remove build artifacts"
	@echo ""
	@echo "Docker:"
	@echo "  make docker-build - Build the Docker image"
	@echo "  make docker-push  - Build and push image to GHCR"
	@echo "  make docker-login - Log into GitHub Container Registry"
	@echo ""
	@echo "Deploy:"
	@echo "  make deploy       - Deploy with Kamal (V=<sha>, default: current HEAD)"
	@echo "  make deploy-setup - First-time Kamal setup on server"
	@echo "  make deploy-logs  - Tail application logs"
	@echo "  make deploy-details - Show running containers"

include makefiles/build.mk
include makefiles/test.mk
include makefiles/ci.mk
include makefiles/docker.mk
include makefiles/deploy.mk
