GIT_SHA ?= $(shell git rev-parse --short HEAD)
V ?= $(GIT_SHA)

.PHONY: deploy deploy-setup deploy-logs deploy-details

deploy:
	kamal deploy -P --version=$(V)

deploy-setup:
	kamal setup -P --version=$(V)

deploy-logs:
	kamal app logs

deploy-details:
	kamal details
