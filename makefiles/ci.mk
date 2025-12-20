# CI targets

.PHONY: check ci

# Run all checks (build + test + format-check + lint)
check: build test format-check lint

# Clean build + full check
ci: clean check
