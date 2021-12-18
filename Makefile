
META := $(shell git rev-parse --abbrev-ref HEAD 2> /dev/null | sed 's:.*/::')
VERSION := $(shell git fetch --tags --force 2> /dev/null; tags=($$(git tag --sort=-v:refname)) && ([ "$${\#tags[@]}" -eq 0 ] && echo v0.0.0 || echo $${tags[0]}) | sed -e "s/^v//")
ARCH := $(shell uname -m | sed 's/x86_64/amd64/' | sed 's/aarch64/arm64/')

export COMPOSE_DOCKER_CLI_BUILD = 1
export DOCKER_BUILDKIT = 1
export COMPOSE_PROJECT_NAME = data-warehouse

.ONESHELL:
.PHONY: arm64
.PHONY: amd64

.PHONY: all
all: bootstrap sync test package bbtest

.PHONY: package
package:
	@$(MAKE) bundle-binaries-$(ARCH)
	@$(MAKE) bundle-debian-$(ARCH)
	@$(MAKE) bundle-docker-$(ARCH)

.PHONY: bundle-binaries-%
bundle-binaries-%: %
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm package \
		--arch linux/$^ \
		--source /project/services/data-warehouse \
		--output /project/packaging/bin

.PHONY: bundle-debian-%
bundle-debian-%: %
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm debian-package \
		--version $(VERSION) \
		--arch $^ \
		--pkg data-warehouse \
		--source /project/packaging

.PHONY: bundle-docker-%
bundle-docker-%: %
	@docker build \
		-t openbank/data-warehouse:$^-$(VERSION).$(META) \
		-f packaging/docker/$^/Dockerfile \
		.

.PHONY: bootstrap
bootstrap:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose build --force-rm scala

.PHONY: lint
lint:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm lint \
		--source /project/services/data-warehouse \
	|| :

.PHONY: sec
sec:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm sec \
		--source /project/services/data-warehouse \
	|| :

.PHONY: sync
sync:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm sync \
		--source /project/services/data-warehouse

.PHONY: scan-%
scan-%: %
	docker scan \
	  openbank/data-warehouse:$^-$(VERSION).$(META) \
	  --file ./packaging/docker/$^/Dockerfile \
	  --exclude-base

.PHONY: test
test:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm test \
		--source /project/services/data-warehouse \
		--output /project/reports/unit-tests

.PHONY: release
release:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose \
		run \
		--rm release \
		--version $(VERSION) \
		--token ${GITHUB_RELEASE_TOKEN}

.PHONY: bbtest
bbtest:
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose up -d bbtest
	@docker exec -t $$(ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose ps -q bbtest) python3 /opt/app/bbtest/main.py
	@ARCH=$(ARCH) META=$(META) VERSION=$(VERSION) docker-compose down -v
