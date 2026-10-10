# @find: makefile, make targets, build, test, lint, format, up, down, logs, restart, clean, dev-backend, web-dev, web-build, web-test, design-check, verify, docker compose commands, developer commands
# @what: Developer command shortcuts for building, testing, formatting, running the Docker stack and the web client.
# @flow: Calls ./mvnw, infra/compose/docker-compose.yml, scripts/dev-backend.sh and pnpm in web/
# AI Workforce OS
#
# Every target here is meant to work on a machine that has just cloned the repository, with no
# credentials configured. If a target needs a secret to be useful, it says so rather than failing
# with a stack trace.

SHELL := /bin/bash
COMPOSE := docker compose -f infra/compose/docker-compose.yml
MVN := ./mvnw
SERVICES := gateway identity-service org-service orchestrator-service memory-service \
            knowledge-service integrations-service analytics-service

.DEFAULT_GOAL := help
.PHONY: help build test test-it lint format up down logs ps restart clean dev-backend dev-status \
        dev-stop deps web-install web-dev web-build web-test design-check verify

help: ## Show the available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---- Backend ---------------------------------------------------------------------------------

# The format check is not part of build: it is `make lint`, and CI should run it as -Pformat-check.
# That keeps `make build` working on a JDK newer than the 21 in .java-version, where the
# formatter itself cannot run.
build: ## Compile and package every module
	$(MVN) -B -ntp install -DskipTests

test: ## Run unit and slice tests
	$(MVN) -B -ntp test

test-it: ## Run integration tests (needs Docker for Testcontainers)
	$(MVN) -B -ntp verify -Pit

lint: ## Check formatting (needs JDK 21, see .java-version)
	$(MVN) -B -ntp spotless:check

format: ## Apply the code format
	$(MVN) -B -ntp spotless:apply

verify: lint test ## Check formatting, then run unit and slice tests

# ---- Stack -----------------------------------------------------------------------------------

up: ## Start the whole platform locally, with no credentials required
	$(COMPOSE) up -d --build
	@echo ""
	@echo "  Gateway and API docs   http://localhost:8080/swagger-ui.html"
	@echo "  Web client             http://localhost:5173  (run 'make web-dev')"
	@echo "  Grafana                http://localhost:3001"
	@echo ""
	@echo "  Every agent runs on the offline sandbox model until a provider key is added"
	@echo "  in Settings. Nothing else needs configuring."

down: ## Stop the stack, keeping the data
	$(COMPOSE) down

clean: ## Stop the stack and delete its data
	$(COMPOSE) down -v
	$(MVN) -B -ntp clean

logs: ## Follow the logs of every service
	$(COMPOSE) logs -f --tail=100

ps: ## Show what is running
	$(COMPOSE) ps

restart: ## Rebuild and restart one service, for example: make restart SERVICE=identity
	@test -n "$(SERVICE)" || (echo "Set SERVICE, for example: make restart SERVICE=identity" && exit 1)
	$(COMPOSE) up -d --build $(SERVICE)

dev-backend: ## Run the seven services locally without Docker (needs PostgreSQL on 55432; ARGS=--with-gateway adds the gateway)
	scripts/dev-backend.sh $(ARGS)

dev-status: ## Show which local services are ready
	scripts/dev-backend.sh status

dev-stop: ## Stop the local services started by dev-backend
	scripts/dev-backend.sh stop

# ---- Web client ------------------------------------------------------------------------------

web-install: ## Install the web client's dependencies
	cd web && pnpm install

web-dev: ## Run the web client against the local stack
	cd web && pnpm dev

web-build: ## Build the web client for production
	cd web && pnpm build

web-test: ## Run the web client's tests
	cd web && pnpm test

design-check: ## Assert the design-system rules on every screen
	cd web && pnpm design-check
