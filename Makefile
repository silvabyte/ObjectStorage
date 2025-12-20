# ObjectStorage Makefile
# Modular build system for Scala project

.PHONY: help
help:
	@echo "ObjectStorage Build System"
	@echo ""
	@echo "Build & Format:"
	@echo "  make build        - Compile the project"
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

include makefiles/build.mk
include makefiles/test.mk
include makefiles/ci.mk
