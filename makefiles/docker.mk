# Docker build and push targets

-include .env.docker

GIT_SHA := $(shell git rev-parse --short HEAD)
IMAGE_NAME := ghcr.io/$(CR_NAMESPACE)/objectstorage

.PHONY: docker-login docker-build docker-push

# Log into GitHub Container Registry
docker-login:
	@echo "Logging into ghcr.io..."
	@echo $(CR_PAT) | docker login ghcr.io -u $(CR_USER) --password-stdin

# Build the Docker image
docker-build:
	docker build -t $(IMAGE_NAME):latest -t $(IMAGE_NAME):$(GIT_SHA) -f docker/Dockerfile .

# Build and push the Docker image to GHCR
docker-push: docker-login docker-build
	docker push $(IMAGE_NAME):latest
	docker push $(IMAGE_NAME):$(GIT_SHA)
