# ObjectStorage Makefile
# Modular build system for Scala project

.PHONY: help
help:
	@echo "ObjectStorage Build System"
	@echo ""
	@echo "Build & Run:"
	@echo "  make build        - Compile the project"
	@echo "  make run          - Run the ObjectStorage server"
	@echo ""
	@echo "Format & Lint:"
	@echo "  make format       - Format code with Scalafmt"
	@echo "  make fmt          - Alias for format"
	@echo "  make format-check - Check formatting without changes"
	@echo "  make lint         - Run Scalafix linter"
	@echo "  make fix          - Auto-fix linting issues"
	@echo ""
	@echo "Testing:"
	@echo "  make test         - Run all tests"
	@echo "  make test-only T= - Run specific test (e.g., make test-only T=ConfigTest)"
	@echo ""
	@echo "CI:"
	@echo "  make check        - Run build + test + format-check + lint"
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
