# Система мониторинга производительности распределённых систем

Дипломный проект — сервер сбора, хранения, анализа и визуализации показателей
работы распределённых систем: микросервисных архитектур, облачных инфраструктур
и кластеров вычислительных узлов.

Реализован как Proof of Concept на **Spring Boot 3** + **Java 17** + **Spring Data JPA**.
Все данные (пользователи, узлы, метрики, правила и алерты) хранятся в реляционной базе:

- **по умолчанию** — встроенный H2 в файле `./data/monitoring.mv.db` (живёт между перезапусками);
- профиль `dev` — H2 + web-консоль на `/h2-console`;
- профиль `test` — H2 in-memory (PostgreSQL-mode) для прогона тестов;
- профиль `postgres` — внешний PostgreSQL (см. раздел [База данных и индексы](#база-данных-и-индексы)).

Таблица метрик устроена как time-series и покрыта тремя композитными индексами,
которые позволяют отрисовывать графики за миллисекунды на десятках тысяч точек.

---

## Содержание

1. [Архитектура](#архитектура)
2. [Требования](#требования)
3. [Запуск](#запуск)
4. [Makefile — команды](#makefile--команды)
5. [Многопользовательский режим и аутентификация](#многопользовательский-режим-и-аутентификация)
6. [База данных и индексы](#база-данных-и-индексы)
7. [Быстрый старт (demo-seed)](#быстрый-старт-demo-seed)
8. [REST API](#rest-api)
   - [Auth — регистрация и вход](#auth--регистрация-и-вход)
   - [Nodes — управление узлами](#nodes--управление-узлами)
   - [Metrics — приём и аналитика метрик](#metrics--приём-и-аналитика-метрик)
   - [Alerts — правила и активные алерты](#alerts--правила-и-активные-алерты)
   - [Dashboard — сводка](#dashboard--сводка)
   - [Actuator и служебные эндпоинты](#actuator-и-служебные-эндпоинты)
9. [Модель данных](#модель-данных)
10. [Подключение нового узла](#подключение-нового-узла)
11. [Типичные сценарии использования](#типичные-сценарии-использования)
12. [Тесты](#тесты)
13. [Структура проекта](#структура-проекта)

---

## Архитектура

```
┌────────────┐       POST /metrics         ┌────────────────────┐
│  Узел №1   │ ──────────────────────────▶ │                    │
├────────────┤                             │                    │
│  Узел №2   │ ──── heartbeat ────────────▶│  Monitoring Server │
├────────────┤                             │  (Spring Boot)     │
│  Узел №N   │ ──── heartbeat + metrics ──▶│                    │
└────────────┘                             └─────────┬──────────┘
                                                     │
                  ┌──────────────────────┬───────────┴──────────┬──────────────────────┐
                  ▼                      ▼                      ▼                      ▼
         ┌────────────────┐    ┌─────────────────┐    ┌─────────────────┐    ┌────────────────┐
         │  Spring Data   │    │ Alert evaluator │    │ Heartbeat /     │    │  REST API /    │
         │  JPA + H2 /    │    │  (раз в 10 сек) │    │ retention task  │    │  Swagger UI    │
         │  PostgreSQL    │    │                 │    │ (5 сек / 1 час) │    │                │
         └────────────────┘    └─────────────────┘    └─────────────────┘    └────────────────┘
              ▲                                                                       │
              │                                                                       ▼
              │                                                              ┌───────────────┐
              └────────────── индексы под графики ─────────────────────────▶│   Dashboard   │
                                                                              │  (HTML + JS)  │
                                                                              └───────────────┘
```

**Ключевые компоненты:**

| Слой            | Назначение                                                    |
|-----------------|---------------------------------------------------------------|
| `controller/`   | REST-контроллеры (Auth, Nodes, Metrics, Alerts, Dashboard)    |
| `service/`      | Бизнес-логика: реестр узлов, хранение метрик, алерты          |
| `repository/`   | Spring Data JPA репозитории, JPQL-запросы для графиков        |
| `model/`        | JPA-сущности: `User`, `Node`, `Metric`, `Alert`, `AlertRule`  |
| `persistence/`  | `MapToJsonConverter` для хранения `tags` как JSON             |
| `dto/`          | Контракты API (request/response)                              |
| `security/`     | JWT-фильтр, выпуск/парсинг токенов, текущий пользователь      |
| `config/`       | OpenAPI, CORS, `PasswordEncoder`                              |
| `static/`       | Веб-дашборд (index.html)                                      |

**Фоновые задачи:**
- Каждые **5 секунд** — пересчёт статусов узлов на основе времени последнего heartbeat.
- Каждые **10 секунд** — оценка всех включённых правил алертов.
- Каждый **1 час** — retention: удаление метрик старше `app.metrics.retention-hours` (по умолчанию 168 ч = 7 суток).

---

## Требования

| Компонент | Версия   |
|-----------|----------|
| JDK       | 17+      |
| Maven     | 3.8+     |
| make      | опционально (для `Makefile`) |
| curl      | опционально (для `demo-seed` и скачивания OpenAPI) |

Проверка:
```bash
java -version
mvn -version
```

---

## Запуск

### Вариант 1 — через Makefile (удобно)

```bash
make run          # запустить сервер на :8080
make swagger      # открыть Swagger UI в браузере
make build        # собрать jar
make run-jar      # запустить собранный jar
make clean        # очистить target/
make test         # запустить тесты
```

### Вариант 2 — напрямую через Maven

```bash
mvn spring-boot:run
```

### Вариант 3 — собрать jar и запустить

```bash
mvn clean package -DskipTests
java -jar target/distributed-monitoring.jar
```

Порт по умолчанию — **8080**. Переопределить:
```bash
make run PORT=9090
# или
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
```

После запуска сервер доступен по адресам:

| URL                                                      | Назначение              |
|----------------------------------------------------------|-------------------------|
| [http://localhost:8080/](http://localhost:8080/)         | HTML-дашборд            |
| [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | Swagger UI              |
| [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)         | OpenAPI JSON            |
| [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) | Health check            |

---

## Makefile — команды

| Цель                | Что делает                                                   |
|---------------------|--------------------------------------------------------------|
| `make help`         | Показать справку по целям                                    |
| `make run`          | Запуск локального сервера (`mvn spring-boot:run`)            |
| `make build`        | Сборка jar в `target/`                                       |
| `make run-jar`      | `build` + запуск собранного jar                              |
| `make clean`        | Очистить `target/`                                           |
| `make test`         | Запустить unit-тесты                                         |
| `make swagger`      | Скачать `target/openapi.json` + открыть Swagger UI           |
| `make openapi`      | Только скачать `target/openapi.json` (сервер должен быть запущен) |
| `make swagger-ui`   | Открыть Swagger UI в браузере                                |
| `make demo-seed`    | Создать демо-узел, отправить метрики и настроить правило алерта |

Переопределяемые переменные:
```bash
make run PORT=9090
make swagger BASE_URL=http://localhost:9090
```

---

## Многопользовательский режим и аутентификация

Сервис работает в **multi-tenant** режиме: каждый пользователь видит только
свои узлы, метрики, правила и алерты. Используется JWT в заголовке
`Authorization: Bearer <token>`.

### Роли

| Роль   | Права                                                            |
|--------|------------------------------------------------------------------|
| `USER` | Видит и управляет только собственными узлами / метриками / правилами |
| `ADMIN`| Видит всё; используется для администрирования и отладки           |

### Bootstrap-админ

При старте, если в системе нет администратора, автоматически создаётся:

- username: `admin`
- password: `admin123`

(см. `app.bootstrap-admin.*` в `application.yml` — отключите/смените в продакшене).

### Конфигурация JWT

В `application.yml`:
```yaml
app:
  jwt:
    secret: "..."          # обязательно сменить (мин. 32 символа для HS256)
    ttl-seconds: 86400     # время жизни токена
  bootstrap-admin:
    enabled: true
    username: admin
    password: admin123
```

### Жизненный цикл

1. **Регистрация** — `POST /api/v1/auth/register` → возвращает JWT.
2. Все запросы (кроме `/auth/login`, `/auth/register`, `/swagger-ui`,
   `/v3/api-docs`, `/actuator/health`, `/`) требуют заголовок
   `Authorization: Bearer <token>`.
3. При регистрации узла он автоматически привязывается к `ownerId` = id
   текущего пользователя. Чужие узлы недоступны (404).
4. Метрики тегируются `ownerId` владельца узла на ingest — это позволяет
   быстро фильтровать метрики при запросе и в правилах алертов.
5. Правила алертов также имеют `ownerId`. При создании правила с указанным
   `nodeId` сервер проверяет, что узел принадлежит пользователю.

### Как авторизоваться в Swagger UI

1. Откройте `http://localhost:8080/swagger-ui.html`.
2. Вызовите `POST /api/v1/auth/login` (или `/register`) с телом:
   ```json
   { "username": "admin", "password": "admin123" }
   ```
3. Скопируйте `token` из ответа.
4. Нажмите кнопку **Authorize** (замок) в правом верхнем углу.
5. Введите `<token>` (без префикса `Bearer`) — Swagger сам добавит.
6. Все защищённые ручки теперь будут отправляться с правильным заголовком.

### Как авторизоваться в HTML-дашборде

Открыть `http://localhost:8080/` — появится экран входа. Логин/пароль
сохраняется в `localStorage` (только токен, не пароль).

---

## База данных и индексы

### Какая БД используется

Сервис работает поверх **Spring Data JPA** + **Hibernate 6**. На стороне СУБД
поддерживаются два варианта:

| Профиль   | СУБД                              | Когда использовать                            |
|-----------|-----------------------------------|-----------------------------------------------|
| (default) | H2 в файле `./data/monitoring.mv.db` | локальный запуск, между перезапусками не теряет данные |
| `dev`     | H2 + web-консоль `/h2-console`    | разработка, ручная инспекция таблиц           |
| `test`    | H2 in-memory (`MODE=PostgreSQL`)  | прогон автотестов, БД пересоздаётся на каждый запуск |
| `postgres`| PostgreSQL                        | продакшен / нагрузочный стенд                 |

H2 в режиме `MODE=PostgreSQL` намеренно ведёт себя как PostgreSQL (типы, кавычки,
поведение `null`) — это позволяет писать код один раз и не натыкаться на различия
между прод-окружением и тестами.

#### Запуск с PostgreSQL

```bash
# в одном терминале:
docker run --rm -e POSTGRES_USER=monitoring -e POSTGRES_PASSWORD=monitoring \
  -e POSTGRES_DB=monitoring -p 5432:5432 postgres:16

# в другом:
SPRING_PROFILES_ACTIVE=postgres mvn spring-boot:run
```

Параметры подключения настраиваются в `application.yml` под профилем `postgres`.

#### Схема

Hibernate сам создаёт/обновляет таблицы (`spring.jpa.hibernate.ddl-auto: update`):

| Таблица        | Что хранит                                               |
|----------------|----------------------------------------------------------|
| `users`        | Пользователи: `id`, `username` (uniq), `password_hash`, `role`, `created_at` |
| `nodes`        | Узлы: `id`, `owner_id`, `name`, `host`, `port`, `type`, `status`, `tags_json`, `registered_at`, `last_heartbeat` |
| `metrics`      | Time-series точки наблюдений (см. ниже)                  |
| `alert_rules`  | Правила: пороги, продолжительность, severity, `enabled`  |
| `alerts`       | История срабатываний: `FIRING` / `RESOLVED`              |

Поля-словари (`tags`) хранятся как JSON в `TEXT`-колонке через
`MapToJsonConverter` (JPA `AttributeConverter`) — это работает одинаково
на H2 и PostgreSQL без специфики `jsonb`.

### Как индексы ускоряют графики

Самая «горячая» таблица — `metrics`: в неё пишутся все замеры со всех узлов
всех пользователей, и по ней строятся графики на дашборде. Без индексов
запрос «дай мне `cpu.usage` за последние 15 минут по узлу X» приводит к
полному скану таблицы — `O(N)` от общего количества метрик в системе.

В JPA-сущности `Metric` объявлены **три композитных индекса**:

```java
@Table(name = "metrics", indexes = {
    @Index(name = "idx_metrics_owner_name_ts", columnList = "owner_id, name, timestamp"),
    @Index(name = "idx_metrics_node_name_ts",  columnList = "node_id,  name, timestamp"),
    @Index(name = "idx_metrics_ts",            columnList = "timestamp")
})
```

#### 1. `idx_metrics_owner_name_ts (owner_id, name, timestamp)`

Это главный индекс под чтение графиков. Под него попадает запрос вида:

```sql
SELECT * FROM metrics
WHERE owner_id = ? AND name = ? AND timestamp BETWEEN ? AND ?
ORDER BY timestamp ASC;
```

Что тут выгодного с точки зрения B-tree:

- **Equality по `owner_id` и `name`** — по первым двум колонкам индекса
  планировщик мгновенно «срезает» нужный слайс таблицы (`O(log N)`).
- **Range по `timestamp`** — третья колонка индекса, в B-tree её диапазон
  читается последовательно, без random I/O.
- **`ORDER BY timestamp` бесплатно** — индекс уже отсортирован по этой колонке,
  значит сортировки в плане выполнения вообще не будет.

В сумме: вместо «прочитать миллион строк, отфильтровать, отсортировать»
БД делает «найди начало диапазона, читай подряд до конца диапазона».
Это и есть ответ на вопрос «как сделать графики быстрыми».

#### 2. `idx_metrics_node_name_ts (node_id, name, timestamp)`

Тот же приём, но когда график строится по конкретному узлу
(латест-метрики на странице узла, или оценка правила алерта с заданным `nodeId`).
Запрос:

```sql
SELECT * FROM metrics
WHERE node_id = ? AND name = ? AND timestamp >= ?
ORDER BY timestamp DESC;
```

Зачем отдельный индекс, если есть `(owner_id, name, timestamp)`? Потому что
B-tree эффективен только когда фильтр идёт по **префиксу** колонок индекса.
Запрос «по узлу без owner» по первому индексу не пойдёт — поэтому второй
индекс берёт на себя «по узлу». Дисковая стоимость двух индексов невелика
по сравнению с выигрышем по чтению.

#### 3. `idx_metrics_ts (timestamp)`

Нужен для retention-задачи и любых time-window запросов без фильтра по owner/node:

```sql
DELETE FROM metrics WHERE timestamp < ?;   -- retention раз в час
```

B-tree по `timestamp` отвечает «верни все строки до момента T» одним range scan-ом.

#### Порядок колонок имеет значение

Это, наверное, главное, что стоит запомнить. У композитного индекса
`(owner_id, name, timestamp)`:

- запрос `WHERE owner_id = ? AND name = ?` — **попадает** в индекс;
- запрос `WHERE owner_id = ? AND timestamp > ?` — **частично попадает** (использует только `owner_id`);
- запрос `WHERE name = ?` (без owner) — **не попадает**, нужен другой индекс.

Поэтому колонки расположены от самой селективной к наименее селективной:
сначала «отрезаем» данные одного пользователя, потом одну метрику,
потом сужаем диапазон времени.

### Эндпоинт `/api/v1/metrics/timeseries`

Чтобы возвращать данные сразу под формат графика, добавлен эндпоинт:

```
GET /api/v1/metrics/timeseries?name=cpu.usage&nodeId=...&from=...&to=...&bucketSeconds=60
```

Что он делает:

1. Делает один индексный range-scan по `idx_metrics_owner_name_ts`
   (или `idx_metrics_node_name_ts`, если задан `nodeId`).
2. За один проход в Java группирует точки по бакетам длиной `bucketSeconds`,
   считая `count`, `min`, `max`, `avg`, `sum`, `last` для каждого бакета.
3. Возвращает массив `{ start, end, count, min, max, avg, last }`,
   готовый к подаче в Chart.js / d3.

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/metrics/timeseries?name=cpu.usage&bucketSeconds=60"
```

Группировка делается на стороне приложения (а не через `date_trunc` /
оконные функции), потому что:
- даёт одинаковый результат на H2 и PostgreSQL без диалект-зависимого SQL;
- индекс уже отдал отсортированный поток — достаточно одного прохода `O(N)`.

### Retention — автоматическое удаление старых метрик

Чтобы таблица `metrics` не росла бесконечно, `HeartbeatMonitor` раз в час
вызывает `metricService.deleteOlderThan(cutoff)`. Параметр настраивается:

```yaml
app:
  metrics:
    retention-hours: 168    # 7 дней по умолчанию
```

Эта задача упирается ровно в третий индекс — `idx_metrics_ts`.

---

## Быстрый старт (demo-seed)

В одном терминале запустите сервер:
```bash
make run
```

В другом — наполните его демо-данными:
```bash
make demo-seed
```

Эта команда:
1. Зарегистрирует узел `order-service-01`.
2. Отправит 10 значений метрики `cpu.usage` (42…99 %).
3. Создаст правило алерта `HighCPU` — срабатывает при `cpu.usage > 80` в течение 10 секунд.
4. Через ~10 секунд алерт автоматически сработает.

Затем откройте:
- Дашборд — <http://localhost:8080/>
- Swagger UI — <http://localhost:8080/swagger-ui.html>

---

## REST API

Базовый URL: `http://localhost:8080`
Префикс всех ручек: `/api/v1`
Формат: `application/json`

> **Все ручки кроме `/auth/register`, `/auth/login`, Swagger и `/actuator/health`
> требуют заголовок `Authorization: Bearer <JWT-токен>`.**
> Получить токен — через `/api/v1/auth/login`.

### Auth — регистрация и вход

#### `POST /api/v1/auth/register` — регистрация

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"supersecret"}'
```

Ответ:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": "9fc1b6c9-...",
  "username": "alice",
  "role": "USER",
  "expiresInSeconds": 86400
}
```

#### `POST /api/v1/auth/login` — вход

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"supersecret"}'
```

Сохраните `token` — далее передавайте его в заголовке:
```bash
TOKEN="eyJhbGciOi..."
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/nodes
```

#### `GET /api/v1/auth/me` — кто я

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/auth/me
```

---

### Nodes — управление узлами

> Все ручки требуют JWT. Возвращаются только узлы, принадлежащие текущему
> пользователю (либо все — для роли `ADMIN`).

#### `POST /api/v1/nodes` — зарегистрировать узел

**Тело запроса:**
```json
{
  "name": "order-service-01",
  "host": "10.0.1.42",
  "port": 8080,
  "type": "service",
  "tags": { "env": "prod", "region": "eu-west-1" }
}
```

**Ответ `200`:**
```json
{
  "id": "9fc1b6c9-...",
  "name": "order-service-01",
  "host": "10.0.1.42",
  "port": 8080,
  "type": "service",
  "tags": { "env": "prod", "region": "eu-west-1" },
  "status": "HEALTHY",
  "registeredAt": "2026-04-21T12:00:00Z",
  "lastHeartbeat": "2026-04-21T12:00:00Z"
}
```

**Пример curl:**
```bash
curl -X POST http://localhost:8080/api/v1/nodes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"order-service-01","host":"10.0.1.42","port":8080,"type":"service"}'
```

> Поле `ownerId` присваивается сервером автоматически из JWT — клиент его
> не передаёт.

---

#### `GET /api/v1/nodes` — список узлов

Параметры:
- `status` (необязательный) — `HEALTHY | DEGRADED | UNHEALTHY | UNKNOWN`.

```bash
curl http://localhost:8080/api/v1/nodes
curl "http://localhost:8080/api/v1/nodes?status=UNHEALTHY"
```

---

#### `GET /api/v1/nodes/{id}` — получить узел по ID

```bash
curl http://localhost:8080/api/v1/nodes/9fc1b6c9-...
```

---

#### `POST /api/v1/nodes/{id}/heartbeat` — подтверждение работоспособности

Узел обязан отправлять heartbeat, иначе через 15 секунд получит статус
`DEGRADED`, через 30 секунд — `UNHEALTHY`.

```bash
curl -X POST http://localhost:8080/api/v1/nodes/9fc1b6c9-.../heartbeat
```

Отправка метрик (`POST /api/v1/metrics/...`) **автоматически** обновляет
heartbeat — отдельный вызов не обязателен.

---

#### `GET /api/v1/nodes/{id}/metrics/latest` — последние значения всех метрик узла

```bash
curl http://localhost:8080/api/v1/nodes/9fc1b6c9-.../metrics/latest
```

---

#### `DELETE /api/v1/nodes/{id}` — снять узел с регистрации

Удаляет узел и все его метрики.
```bash
curl -X DELETE http://localhost:8080/api/v1/nodes/9fc1b6c9-...
```

---

### Metrics — приём и аналитика метрик

#### `POST /api/v1/metrics/nodes/{nodeId}` — одна метрика

**Тело:**
```json
{
  "name": "cpu.usage",
  "value": 73.5,
  "type": "GAUGE",
  "unit": "percent",
  "timestamp": "2026-04-21T12:00:00Z",
  "tags": { "core": "0" }
}
```

`type` допускает: `GAUGE | COUNTER | HISTOGRAM | TIMER`.
`timestamp` необязателен — если не указан, используется серверное время.

```bash
curl -X POST http://localhost:8080/api/v1/metrics/nodes/9fc1b6c9-... \
  -H "Content-Type: application/json" \
  -d '{"name":"cpu.usage","value":73.5,"unit":"percent"}'
```

---

#### `POST /api/v1/metrics/batch` — пакетная отправка

Эффективно при частых метриках:
```json
{
  "nodeId": "9fc1b6c9-...",
  "metrics": [
    { "name": "cpu.usage",      "value": 73.5, "unit": "percent" },
    { "name": "memory.used",    "value": 1024, "unit": "MB"      },
    { "name": "request.latency","value":   42, "unit": "ms", "type": "TIMER" }
  ]
}
```

```bash
curl -X POST http://localhost:8080/api/v1/metrics/batch \
  -H "Content-Type: application/json" \
  -d @batch.json
```

---

#### `GET /api/v1/metrics` — запрос сырых значений

Параметры:
| Параметр | Описание                                                  |
|----------|-----------------------------------------------------------|
| `nodeId` | фильтр по узлу                                            |
| `name`   | фильтр по имени метрики                                   |
| `from`   | начало окна (ISO-8601, напр. `2026-04-21T10:00:00Z`)      |
| `to`     | конец окна (ISO-8601)                                     |
| `limit`  | максимум записей (по умолчанию 500)                       |

```bash
curl "http://localhost:8080/api/v1/metrics?name=cpu.usage&limit=100"

curl "http://localhost:8080/api/v1/metrics?nodeId=9fc1b6c9-...&name=cpu.usage&from=2026-04-21T12:00:00Z&to=2026-04-21T13:00:00Z"
```

---

#### `GET /api/v1/metrics/timeseries` — данные для графика (с бакетингом)

Возвращает значения метрики, сгруппированные по временным бакетам — формат,
который удобно отдавать в Chart.js / d3 без дополнительной обработки на клиенте.

Параметры:
| Параметр        | Описание                                           |
|-----------------|----------------------------------------------------|
| `name`          | имя метрики (обязательно)                          |
| `nodeId`        | фильтр по узлу (необязательно)                     |
| `from`, `to`    | окно времени (ISO-8601). По умолчанию — последний час |
| `bucketSeconds` | размер бакета. По умолчанию `60`                   |

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/metrics/timeseries?name=cpu.usage&bucketSeconds=60"
```

Ответ — массив бакетов:
```json
[
  { "start": "2026-04-21T12:00:00Z", "end": "2026-04-21T12:01:00Z",
    "count": 6, "min": 41.0, "max": 73.0, "avg": 58.5, "sum": 351.0, "last": 73.0 },
  { "start": "2026-04-21T12:01:00Z", "end": "2026-04-21T12:02:00Z",
    "count": 5, "min": 71.0, "max": 92.0, "avg": 80.4, "sum": 402.0, "last": 92.0 }
]
```

Запрос делает один индексный range scan (`idx_metrics_owner_name_ts` или
`idx_metrics_node_name_ts`, если задан `nodeId`) и группирует точки в один
проход на стороне приложения — см. [База данных и индексы](#база-данных-и-индексы).

---

#### `GET /api/v1/metrics/summary` — сводная статистика

Считает по окну `min / max / avg / sum / p50 / p95 / p99 / last`.

Параметры:
- `name` — имя метрики (обязательно).
- `nodeId` — фильтр по узлу (необязательно).
- `windowSeconds` — длина окна, по умолчанию `300`.

```bash
curl "http://localhost:8080/api/v1/metrics/summary?name=cpu.usage&windowSeconds=600"
```

Ответ:
```json
{
  "metricName": "cpu.usage",
  "nodeId": null,
  "count": 25,
  "min": 42.0,
  "max": 99.0,
  "avg": 71.6,
  "sum": 1790.0,
  "p50": 72.0,
  "p95": 97.2,
  "p99": 98.8,
  "last": 83.0,
  "windowStart": "2026-04-21T11:55:00Z",
  "windowEnd":   "2026-04-21T12:05:00Z"
}
```

---

#### `GET /api/v1/metrics/names` — список известных имён метрик

```bash
curl http://localhost:8080/api/v1/metrics/names
```

---

### Alerts — правила и активные алерты

> Эти ручки доступны и через UI: внизу дашборда есть карточка
> «Правила алертов» с кнопкой «+ Создать правило» и кнопками
> «Изменить» / «Удалить» в каждой строке. UI делает ровно те же
> вызовы REST API — описание ниже остаётся актуальным для скриптов и CI.

#### `POST /api/v1/alerts/rules` — создать/обновить правило

**Тело:**
```json
{
  "name": "HighCPU",
  "metricName": "cpu.usage",
  "nodeId": null,
  "condition": "GT",
  "threshold": 80,
  "durationSeconds": 30,
  "severity": "CRITICAL",
  "enabled": true,
  "description": "CPU выше 80% дольше 30 секунд"
}
```

- `condition`: `GT | GTE | LT | LTE | EQ | NEQ`.
- `severity`: `INFO | WARNING | CRITICAL`.
- `nodeId = null` — правило применяется ко **всем узлам**.
- `durationSeconds` — сколько секунд все метрики подряд должны нарушать порог,
  прежде чем сработает алерт.

```bash
curl -X POST http://localhost:8080/api/v1/alerts/rules \
  -H "Content-Type: application/json" \
  -d '{"name":"HighCPU","metricName":"cpu.usage","condition":"GT","threshold":80,"durationSeconds":30,"severity":"CRITICAL"}'
```

---

#### `GET /api/v1/alerts/rules` — список правил

```bash
curl http://localhost:8080/api/v1/alerts/rules
```

#### `GET /api/v1/alerts/rules/{id}` — правило по ID

#### `DELETE /api/v1/alerts/rules/{id}` — удалить правило

---

#### `GET /api/v1/alerts/active` — активные (firing) алерты

```bash
curl http://localhost:8080/api/v1/alerts/active
```

#### `GET /api/v1/alerts/history?limit=100` — история алертов

Возвращает все срабатывания (в т. ч. уже resolved), отсортированные по времени.

#### `POST /api/v1/alerts/evaluate` — принудительная оценка всех правил

По умолчанию правила оцениваются фоново каждые 10 секунд; эта ручка —
для интерактивного тестирования.

```bash
curl -X POST http://localhost:8080/api/v1/alerts/evaluate
```

---

### Dashboard — сводка

#### `GET /api/v1/dashboard/overview`

Возвращает всю сводную информацию одним запросом — её использует HTML-дашборд.

```bash
curl http://localhost:8080/api/v1/dashboard/overview
```

Ответ содержит:
- `totalNodes`, `nodeStatusCounts` — счётчики узлов по статусам;
- `activeAlerts`, `alertSeverityCounts` — счётчики активных алертов;
- `totalMetricsStored` — общее число метрик в памяти;
- `nodes` — полный список узлов;
- `recentAlerts` — последние 10 алертов.

---

### Actuator и служебные эндпоинты

Spring Boot Actuator включён. Доступны:

| URL                                    | Описание                         |
|----------------------------------------|----------------------------------|
| `/actuator/health`                     | Статус приложения                |
| `/actuator/info`                       | Версия, метаданные               |
| `/actuator/metrics`                    | Внутренние метрики JVM / HTTP    |
| `/actuator/prometheus`                 | Экспорт в формате Prometheus     |

---

## Модель данных

### User
| Поле          | Тип    | Описание                              |
|---------------|--------|---------------------------------------|
| `id`          | UUID   | первичный ключ                        |
| `username`    | string | уникальное имя                        |
| `passwordHash`| string | bcrypt-хеш (никогда не возвращается)  |
| `role`        | enum   | `USER / ADMIN`                        |
| `createdAt`   | Instant| время создания                        |

### Node
| Поле            | Тип            | Описание                                |
|-----------------|----------------|-----------------------------------------|
| `id`            | UUID           | присваивается сервером                  |
| `ownerId`       | UUID           | id владельца (User), ставится сервером  |
| `name`          | string         | человекочитаемое имя                    |
| `host`, `port`  | string, int    | адрес узла                              |
| `type`          | string         | произвольный тип (service, db, worker…) |
| `tags`          | map            | произвольные метки                      |
| `status`        | enum           | `HEALTHY / DEGRADED / UNHEALTHY / UNKNOWN` |
| `registeredAt`  | Instant        | время регистрации                       |
| `lastHeartbeat` | Instant        | последний heartbeat                     |

### Metric
| Поле        | Тип      | Описание                                       |
|-------------|----------|------------------------------------------------|
| `id`        | long     | автоинкрементный PK (`GenerationType.IDENTITY`) |
| `nodeId`    | UUID     | ID узла-источника                              |
| `ownerId`   | UUID     | владелец узла (копируется на ingest для фильтров и multi-tenancy) |
| `name`      | string   | имя метрики, напр. `cpu.usage`, `http.req.ms`  |
| `value`     | double   | числовое значение (колонка `metric_value` — `value` зарезервировано) |
| `type`      | enum     | `GAUGE / COUNTER / HISTOGRAM / TIMER`          |
| `unit`      | string   | `percent`, `ms`, `bytes`, …                    |
| `timestamp` | Instant  | время замера                                   |
| `tags`      | map      | произвольные метки (хранятся в `tags_json` через `MapToJsonConverter`) |

Композитные индексы:
`(owner_id, name, timestamp)`, `(node_id, name, timestamp)`, `(timestamp)` —
подробное обоснование в разделе [База данных и индексы](#база-данных-и-индексы).

### AlertRule / Alert
См. раздел [Alerts](#alerts--правила-и-активные-алерты).
Алерты хранятся в таблице `alerts` со статусами `FIRING`/`RESOLVED`. Активный
алерт по правилу+узлу ищется через индекс
`idx_alerts_rule_node_status (rule_id, node_id, status)` — поиск выполняется
за `O(log N)` независимо от размера истории.

---

## Подключение нового узла

Самый частый сценарий использования. Решён двумя способами — выбирайте подходящий.

### Способ A — через UI (рекомендованный, 30 секунд)

1. Откройте `http://localhost:8080/` и войдите в систему.
2. В таблице «Список узлов» нажмите кнопку **«+ Добавить узел»**.
3. Введите имя токена (например, `production-fleet`), выберите ОС (Linux/macOS/Windows/Bash) и опционально имя узла. Нажмите «Получить команду установки».
4. Сервер выпустит долгоживущий **agent-токен** с префиксом `agt_…` и сразу покажет его в plain text **один раз**. Скопируйте его.
5. Скопируйте предложенную одну строку установки и запустите её на целевой машине.
6. Через 5–10 секунд узел появится в списке, начнут идти метрики.

Под капотом при «Получить команду» вызывается `POST /api/v1/agent-tokens`,
сервер генерирует 32 случайных байта, формирует `agt_<base64url>`, сохраняет
в БД только SHA-256-хеш, возвращает plain text один раз. UI собирает
готовую install-команду с подставленным URL сервера и токеном.

### Способ B — вручную через REST API (для скриптов, CI, миграций)

```bash
# 0. Логинимся как обычный пользователь
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"supersecret"}' | jq -r '.token')

# 1. Выпускаем agent-токен (его и положим в агенты)
AGT=$(curl -s -X POST http://localhost:8080/api/v1/agent-tokens \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"production-fleet"}' | jq -r '.token')

# 2. Запускаем агент — он сам зарегистрирует узел и начнёт слать метрики
curl -sSL http://localhost:8080/install/agent.py | python3 - \
    --server http://localhost:8080 \
    --token "$AGT" \
    --node-name web-01

# 3. (по желанию) Можно также делать всё сырым REST'ом без агента:
NODE_ID=$(curl -s -X POST http://localhost:8080/api/v1/nodes \
  -H "Authorization: Bearer $AGT" \
  -H "Content-Type: application/json" \
  -d '{"name":"manual-node","type":"service"}' | jq -r '.id')
curl -X POST "http://localhost:8080/api/v1/metrics/nodes/$NODE_ID" \
  -H "Authorization: Bearer $AGT" \
  -H "Content-Type: application/json" \
  -d '{"name":"http.requests","value":1,"type":"COUNTER"}'
```

### Как авторизуются agent-токены

`JwtAuthFilter` различает два вида Bearer-токенов:

| Префикс | Тип | Где живёт | TTL |
|---------|-----|----------|-----|
| `eyJ…` | JWT (HS256, jjwt) | в браузере после `/auth/login` | 24ч |
| `agt_…` | Agent-токен | хеш в `agent_tokens.token_hash`, индекс уникальный | пока не отозван |

Любой эндпоинт, требующий аутентификации, работает с обоими типами токенов
прозрачно — сервис не знает, агент это или пользователь. Это позволяет
повторно использовать всю существующую модель multi-tenancy и
авторизации: agent-токен авторизуется *от имени своего владельца*, плюс
получает дополнительную роль `ROLE_AGENT` (зарезервирована на будущее
для ограничений по эндпоинтам).

### Управление токенами

В UI: тот же модал «Добавить узел», блок «Активные токены» внизу.
Видны имя, дата создания, последнее использование (`lastUsedAt`
обновляется на каждом запросе) и последние 6 символов — этого хватает,
чтобы понять, какой токен где работает. Кнопка «Отозвать» делает мягкое
удаление — поле `revoked` ставится в `true`, дальнейшие запросы с этим
токеном получат 401.

Через API: `GET /api/v1/agent-tokens`, `DELETE /api/v1/agent-tokens/{id}`.

---

## Типичные сценарии использования

### Получить статистику по CPU за последние 15 минут

```bash
curl "http://localhost:8080/api/v1/metrics/summary?name=cpu.usage&windowSeconds=900"
```

### Настроить алерт на p95 времени ответа

```bash
curl -X POST http://localhost:8080/api/v1/alerts/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name":"HighLatencyP95",
    "metricName":"http.latency.p95",
    "condition":"GT",
    "threshold":500,
    "durationSeconds":60,
    "severity":"WARNING"
  }'
```

> Правило срабатывает, если **все** значения `http.latency.p95` в окне 60 секунд > 500 мс.
> Когда значения возвращаются к норме — алерт автоматически разрешается.

### Посмотреть какие узлы сейчас нездоровы

```bash
curl "http://localhost:8080/api/v1/nodes?status=UNHEALTHY"
```

### Посмотреть все сработавшие алерты за сессию

```bash
curl "http://localhost:8080/api/v1/alerts/history?limit=50"
```

---

## Тесты

В проекте 88 автотестов на JUnit 5. Запуск:

```bash
make test       # или
mvn test
```

Тесты разделены на три уровня:

| Папка                         | Что проверяет                                           |
|-------------------------------|---------------------------------------------------------|
| `test/.../repository/`        | `@DataJpaTest` — репозитории и JPQL-запросы напрямую    |
| `test/.../service/`           | `@SpringBootTest` + `@Transactional` — бизнес-логика    |
| `test/.../integration/`       | `@SpringBootTest` + `@AutoConfigureMockMvc` — end-to-end через HTTP/JWT |

Что покрыто:

- **Repository** (`MetricRepositoryTest`, `NodeRepositoryTest`, `AlertRepositoryTest`):
  поиск по owner/узлу/окну времени, latest-per-name, retention `deleteOlderThan`,
  изоляция данных между owner-ами, корректная обработка `null nodeId` в индексе.
- **Service** (`MetricServiceTest`, `NodeServiceTest`, `AlertServiceTest`,
  `UserServiceTest`):
  ingest и аналитика метрик, бакетинг `/timeseries`, evaluate FIRES/RESOLVES,
  удаление правила гасит активный алерт, регистрация/heartbeat/deregister узлов,
  bcrypt-проверка паролей.
- **Integration** (`AuthAndMultiTenancyTest`):
  регистрация → логин → `me`, отказ без токена, изоляция узлов и метрик между
  пользователями (alice не видит данных bob, не может удалить чужой узел).

Все тесты используют профиль `test` — H2 in-memory в режиме PostgreSQL,
БД пересоздаётся между прогонами (`@DirtiesContext` где нужно для не-транзакционных тестов).

---

## Структура проекта

```
diplom/
├── Makefile
├── pom.xml
├── README.md
├── data/                                     # H2-файл (создаётся при первом запуске)
└── src/
    ├── main/
    │   ├── java/ru/diplom/monitoring/
    │   │   ├── MonitoringApplication.java    # точка входа Spring Boot
    │   │   ├── config/
    │   │   │   ├── OpenApiConfig.java        # OpenAPI/Swagger + Bearer schema
    │   │   │   ├── PasswordConfig.java       # BCryptPasswordEncoder bean
    │   │   │   └── WebConfig.java            # CORS
    │   │   ├── security/
    │   │   │   ├── SecurityConfig.java       # Spring Security filter chain
    │   │   │   ├── JwtService.java           # выпуск/парсинг JWT
    │   │   │   ├── JwtAuthFilter.java        # фильтр Authorization: Bearer
    │   │   │   └── CurrentUser.java          # хелпер получения user из контекста
    │   │   ├── persistence/
    │   │   │   └── MapToJsonConverter.java   # JPA AttributeConverter Map<->JSON
    │   │   ├── repository/
    │   │   │   ├── UserRepository.java       # Spring Data JPA репозитории
    │   │   │   ├── NodeRepository.java
    │   │   │   ├── MetricRepository.java     # JPQL под графики и retention
    │   │   │   ├── AlertRepository.java
    │   │   │   └── AlertRuleRepository.java
    │   │   ├── controller/
    │   │   │   ├── AuthController.java       # /auth/register, /auth/login, /auth/me
    │   │   │   ├── NodeController.java
    │   │   │   ├── MetricController.java     # /metrics, /metrics/timeseries, /metrics/summary
    │   │   │   ├── AlertController.java
    │   │   │   └── DashboardController.java
    │   │   ├── dto/
    │   │   │   ├── AuthRequest.java          AuthResponse.java
    │   │   │   ├── RegisterNodeRequest.java
    │   │   │   ├── MetricIngestRequest.java
    │   │   │   ├── MetricBatchRequest.java
    │   │   │   ├── MetricSummary.java
    │   │   │   ├── TimeSeriesPoint.java      # бакет для /timeseries
    │   │   │   └── DashboardOverview.java
    │   │   ├── model/                        # JPA-сущности
    │   │   │   ├── User.java          Role.java
    │   │   │   ├── Node.java          NodeStatus.java
    │   │   │   ├── Metric.java        MetricType.java
    │   │   │   ├── Alert.java         AlertStatus.java
    │   │   │   ├── AlertRule.java     AlertSeverity.java
    │   │   │   └── Condition.java
    │   │   ├── service/
    │   │   │   ├── UserService.java          # регистрация, поиск, bootstrap-admin
    │   │   │   ├── NodeService.java          # реестр узлов с фильтром по владельцу
    │   │   │   ├── MetricService.java        # ingest, аналитика, бакетинг
    │   │   │   ├── AlertService.java         # правила и оценка алертов
    │   │   │   └── HeartbeatMonitor.java     # @Scheduled: heartbeat + retention
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java
    │   │       └── NotFoundException.java
    │   └── resources/
    │       ├── application.yml               # default(H2) + dev/test/postgres профили
    │       └── static/
    │           └── index.html                # HTML-дашборд
    └── test/
        └── java/ru/diplom/monitoring/
            ├── MonitoringApplicationTests.java
            ├── repository/                   # @DataJpaTest
            ├── service/                      # @SpringBootTest + @Transactional
            └── integration/                  # @SpringBootTest + MockMvc end-to-end
```
