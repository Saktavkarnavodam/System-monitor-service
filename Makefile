# ==============================================================================
# Distributed Systems Monitoring — Makefile (cross-platform: Windows + Unix)
# ==============================================================================

MVN        ?= mvn
PORT       ?= 8080
BASE_URL   ?= http://localhost:$(PORT)
OPENAPI_OUT?= target/openapi.json
SWAGGER_UI ?= $(BASE_URL)/swagger-ui.html

# ---- Детектирование ОС -----------------------------------------------------
# В Windows через GnuWin32 make переменная $(OS) = Windows_NT.
ifeq ($(OS),Windows_NT)
    MKDIR_TARGET = if not exist target mkdir target
    OPEN_CMD     = cmd /c start "" "$(SWAGGER_UI)"
    RM_TARGET    = if exist target rmdir /s /q target
else
    MKDIR_TARGET = mkdir -p target
    OPEN_CMD     = (command -v xdg-open >/dev/null 2>&1 && xdg-open "$(SWAGGER_UI)") \
                || (command -v open >/dev/null 2>&1 && open "$(SWAGGER_UI)") \
                || echo "Откройте вручную: $(SWAGGER_UI)"
    RM_TARGET    = rm -rf target
endif

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo Доступные цели:
	@echo   make run            - запустить сервер локально (spring-boot:run)
	@echo   make run-jar        - собрать и запустить jar
	@echo   make build          - собрать проект (mvn package)
	@echo   make clean          - очистить target/
	@echo   make test           - запустить тесты
	@echo   make swagger        - скачать openapi.json и открыть Swagger UI
	@echo   make openapi        - только скачать openapi.json в $(OPENAPI_OUT)
	@echo   make swagger-ui     - открыть Swagger UI в браузере
	@echo   make demo-seed      - наполнить сервер тестовыми данными
	@echo   make agent-install  - установить зависимости Python-агента (pip install psutil)
	@echo   make agent          - запустить Python-агент на ЭТОЙ машине
	@echo   make agent-win      - запустить PowerShell-агент (Windows)

.PHONY: run
run:
	$(MVN) spring-boot:run -Dspring-boot.run.arguments="--server.port=$(PORT)"

.PHONY: build
build:
	$(MVN) clean package -DskipTests

.PHONY: run-jar
run-jar: build
	java -jar target/distributed-monitoring.jar --server.port=$(PORT)

.PHONY: clean
clean:
	$(MVN) clean

.PHONY: test
test:
	$(MVN) test

# --------------------------------------------------------------------------
# Swagger / OpenAPI
# --------------------------------------------------------------------------

.PHONY: swagger
swagger: openapi swagger-ui

.PHONY: openapi
openapi:
	@$(MKDIR_TARGET)
	@echo Запрашиваем $(BASE_URL)/v3/api-docs ...
	@curl --fail --silent --show-error "$(BASE_URL)/v3/api-docs" -o "$(OPENAPI_OUT)"
	@echo OpenAPI спецификация сохранена в $(OPENAPI_OUT)

.PHONY: swagger-ui
swagger-ui:
	@echo Swagger UI: $(SWAGGER_UI)
	@$(OPEN_CMD)

# --------------------------------------------------------------------------
# Быстрое наполнение тестовыми данными для демо
# --------------------------------------------------------------------------

DEMO_USER     ?= admin
DEMO_PASSWORD ?= admin123

.PHONY: demo-seed
demo-seed:
ifeq ($(OS),Windows_NT)
	@echo demo-seed на Windows недоступен в cmd.exe - используйте Git Bash или PowerShell.
	@echo Альтернатива: откройте Swagger UI и создавайте узлы/метрики через UI.
else
	@echo "Логинимся как $(DEMO_USER)..."
	@TOKEN=$$(curl -s -X POST "$(BASE_URL)/api/v1/auth/login" \
		-H "Content-Type: application/json" \
		-d "{\"username\":\"$(DEMO_USER)\",\"password\":\"$(DEMO_PASSWORD)\"}" \
		| sed -E 's/.*"token":"([^"]+)".*/\1/'); \
	if [ -z "$$TOKEN" ] || [ "$$TOKEN" = "null" ]; then \
		echo "Не удалось залогиниться. Проверьте DEMO_USER/DEMO_PASSWORD."; exit 1; \
	fi; \
	echo "Регистрируем узел order-service-01..."; \
	NODE_ID=$$(curl -s -X POST "$(BASE_URL)/api/v1/nodes" \
		-H "Authorization: Bearer $$TOKEN" \
		-H "Content-Type: application/json" \
		-d '{"name":"order-service-01","host":"10.0.1.10","port":8080,"type":"service","tags":{"env":"prod"}}' \
		| sed -E 's/.*"id":"([^"]+)".*/\1/'); \
	echo "node id = $$NODE_ID"; \
	for v in 42 55 67 71 78 85 90 93 97 99; do \
		curl -s -X POST "$(BASE_URL)/api/v1/metrics/nodes/$$NODE_ID" \
			-H "Authorization: Bearer $$TOKEN" \
			-H "Content-Type: application/json" \
			-d "{\"name\":\"cpu.usage\",\"value\":$$v,\"unit\":\"percent\"}" > /dev/null; \
	done; \
	curl -s -X POST "$(BASE_URL)/api/v1/alerts/rules" \
		-H "Authorization: Bearer $$TOKEN" \
		-H "Content-Type: application/json" \
		-d "{\"name\":\"HighCPU\",\"metricName\":\"cpu.usage\",\"condition\":\"GT\",\"threshold\":80,\"durationSeconds\":10,\"severity\":\"CRITICAL\"}" > /dev/null; \
	echo "Готово. Dashboard: $(BASE_URL)/   Swagger: $(SWAGGER_UI)"
endif

# --------------------------------------------------------------------------
# Агент — сбор реальных метрик с этой машины
# --------------------------------------------------------------------------

AGENT_USER     ?= admin
AGENT_PASSWORD ?= admin123
AGENT_NODE     ?= $(shell hostname)
AGENT_INTERVAL ?= 10

.PHONY: agent-install
agent-install:
	pip install psutil

.PHONY: agent
agent:
	python agent/agent.py \
		--server $(BASE_URL) \
		--username $(AGENT_USER) \
		--password $(AGENT_PASSWORD) \
		--node-name "$(AGENT_NODE)" \
		--interval $(AGENT_INTERVAL)

.PHONY: agent-win
agent-win:
	powershell -ExecutionPolicy Bypass -File agent\agent.ps1 \
		-Server "$(BASE_URL)" \
		-Username "$(AGENT_USER)" \
		-Password "$(AGENT_PASSWORD)" \
		-NodeName "$(AGENT_NODE)" \
		-Interval $(AGENT_INTERVAL)
