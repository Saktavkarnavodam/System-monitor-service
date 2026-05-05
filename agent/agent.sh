#!/usr/bin/env bash
# Агент мониторинга для Linux/macOS
# Собирает реальные метрики и отправляет в сервер мониторинга.
#
# Требования: curl, bc (обычно предустановлены)
#
# Использование (рекомендованный путь — agent-токен):
#   chmod +x agent.sh
#   ./agent.sh --server http://my-server:8080 --token agt_xxx --node-name web-01
#
# Старый путь (логин/пароль) — оставлен для обратной совместимости:
#   ./agent.sh --server http://my-server:8080 --username alice --password secret --node-name web-01

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
SERVER="http://localhost:8080"
TOKEN_ARG=""
USERNAME=""
PASSWORD=""
NODE_NAME="$(hostname)"
HOST_ADDR="$(hostname)"
PORT=0
INTERVAL=10

while [[ $# -gt 0 ]]; do
  case $1 in
    --server)    SERVER="$2";    shift 2 ;;
    --token)     TOKEN_ARG="$2"; shift 2 ;;
    --username)  USERNAME="$2";  shift 2 ;;
    --password)  PASSWORD="$2";  shift 2 ;;
    --node-name) NODE_NAME="$2"; shift 2 ;;
    --host)      HOST_ADDR="$2"; shift 2 ;;
    --port)      PORT="$2";      shift 2 ;;
    --interval)  INTERVAL="$2";  shift 2 ;;
    *) echo "Неизвестный аргумент: $1"; exit 1 ;;
  esac
done

TOKEN=""
TOKEN_KIND=""
NODE_ID=""

# ── HTTP helpers ──────────────────────────────────────────────────────────────
api_post() {
  local path="$1" body="$2" auth_header=""
  [[ -n "$TOKEN" ]] && auth_header="-H 'Authorization: Bearer $TOKEN'"
  eval curl -sf -X POST "$SERVER$path" \
       -H "'Content-Type: application/json'" \
       $auth_header \
       -d "'$body'"
}

api_get() {
  local path="$1"
  curl -sf -X GET "$SERVER$path" \
       -H "Authorization: Bearer $TOKEN"
}

# ── Auth ──────────────────────────────────────────────────────────────────────
login() {
  printf "  Вход как $USERNAME... "
  local body="{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
  local resp
  resp=$(curl -sf -X POST "$SERVER/api/v1/auth/login" \
              -H "Content-Type: application/json" \
              -d "$body" 2>/dev/null) || resp=""
  if [[ -n "$resp" ]]; then
    TOKEN=$(echo "$resp" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
    echo "OK"
    return
  fi

  echo "нет аккаунта — регистрируемся..."
  resp=$(curl -sf -X POST "$SERVER/api/v1/auth/register" \
              -H "Content-Type: application/json" \
              -d "$body")
  TOKEN=$(echo "$resp" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  echo "OK"
}

# ── Node ──────────────────────────────────────────────────────────────────────
register_node() {
  local existing
  existing=$(curl -sf "$SERVER/api/v1/nodes" \
                  -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "[]")
  local found
  found=$(echo "$existing" | grep -o "\"id\":\"[^\"]*\".*\"name\":\"$NODE_NAME\"" | head -1)
  if [[ -n "$found" ]]; then
    NODE_ID=$(echo "$found" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
    echo "  Найден существующий узел: $NODE_NAME ($NODE_ID)"
    return
  fi

  printf "  Регистрируем новый узел: $NODE_NAME... "
  local body="{\"name\":\"$NODE_NAME\",\"host\":\"$HOST_ADDR\",\"port\":$PORT,\"type\":\"server\",\"tags\":{\"os\":\"linux\"}}"
  local resp
  resp=$(curl -sf -X POST "$SERVER/api/v1/nodes" \
              -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" \
              -d "$body")
  NODE_ID=$(echo "$resp" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
  echo "OK ($NODE_ID)"
}

# ── Collect metrics ───────────────────────────────────────────────────────────
collect_metrics() {
  local metrics="["

  # CPU (средняя загрузка за 1 секунду через /proc/stat)
  local cpu_idle_1 cpu_total_1 cpu_idle_2 cpu_total_2

  read -r _ user1 nice1 system1 idle1 iowait1 irq1 softirq1 _ < /proc/stat
  cpu_idle_1=$idle1
  cpu_total_1=$((user1 + nice1 + system1 + idle1 + iowait1 + irq1 + softirq1))
  sleep 1
  read -r _ user2 nice2 system2 idle2 iowait2 irq2 softirq2 _ < /proc/stat
  cpu_idle_2=$idle2
  cpu_total_2=$((user2 + nice2 + system2 + idle2 + iowait2 + irq2 + softirq2))

  local cpu_delta_idle=$(( cpu_idle_2 - cpu_idle_1 ))
  local cpu_delta_total=$(( cpu_total_2 - cpu_total_1 ))
  local cpu_usage=0
  if [[ $cpu_delta_total -gt 0 ]]; then
    cpu_usage=$(echo "scale=1; (1 - $cpu_delta_idle / $cpu_delta_total) * 100" | bc)
  fi
  metrics+="{\"name\":\"cpu.usage\",\"value\":$cpu_usage,\"unit\":\"percent\",\"type\":\"GAUGE\"},"

  # CPU cores
  local cpu_count
  cpu_count=$(nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo)
  metrics+="{\"name\":\"cpu.count_logical\",\"value\":$cpu_count,\"unit\":\"cores\",\"type\":\"GAUGE\"},"

  # Memory
  local mem_total mem_free mem_buffers mem_cached
  mem_total=$(grep MemTotal /proc/meminfo | awk '{print $2}')
  mem_free=$(grep MemFree /proc/meminfo | awk '{print $2}')
  mem_buffers=$(grep Buffers /proc/meminfo | awk '{print $2}')
  mem_cached=$(grep '^Cached:' /proc/meminfo | awk '{print $2}')
  local mem_used=$(( mem_total - mem_free - mem_buffers - mem_cached ))
  local mem_used_mb=$(echo "scale=1; $mem_used / 1024" | bc)
  local mem_total_mb=$(echo "scale=1; $mem_total / 1024" | bc)
  local mem_pct=$(echo "scale=1; $mem_used / $mem_total * 100" | bc)
  metrics+="{\"name\":\"memory.used_mb\",\"value\":$mem_used_mb,\"unit\":\"MB\",\"type\":\"GAUGE\"},"
  metrics+="{\"name\":\"memory.total_mb\",\"value\":$mem_total_mb,\"unit\":\"MB\",\"type\":\"GAUGE\"},"
  metrics+="{\"name\":\"memory.percent\",\"value\":$mem_pct,\"unit\":\"percent\",\"type\":\"GAUGE\"},"

  # Disk
  local disk_info
  disk_info=$(df -BG / | tail -1)
  local disk_total=$(echo "$disk_info" | awk '{print $2}' | tr -d 'G')
  local disk_used=$(echo "$disk_info" | awk '{print $3}' | tr -d 'G')
  local disk_pct=$(echo "$disk_info" | awk '{print $5}' | tr -d '%')
  metrics+="{\"name\":\"disk.used_gb\",\"value\":$disk_used,\"unit\":\"GB\",\"type\":\"GAUGE\"},"
  metrics+="{\"name\":\"disk.total_gb\",\"value\":$disk_total,\"unit\":\"GB\",\"type\":\"GAUGE\"},"
  metrics+="{\"name\":\"disk.percent\",\"value\":$disk_pct,\"unit\":\"percent\",\"type\":\"GAUGE\"},"

  # Process count
  local procs
  procs=$(ps aux --no-headers 2>/dev/null | wc -l || echo 0)
  metrics+="{\"name\":\"process.count\",\"value\":$procs,\"unit\":\"count\",\"type\":\"GAUGE\"}"

  metrics+="]"
  echo "$metrics"
}

# ── Main loop ─────────────────────────────────────────────────────────────────
echo "=== Агент мониторинга (Bash/Linux) ==="
echo "  Сервер:   $SERVER"
echo "  Узел:     $NODE_NAME ($HOST_ADDR)"
echo "  Интервал: $INTERVAL сек"
echo ""

if [[ -n "$TOKEN_ARG" ]]; then
  if [[ "$TOKEN_ARG" != agt_* ]]; then
    echo "WARN: --token обычно начинается с 'agt_'. Возможно, передан JWT?"
  fi
  TOKEN="$TOKEN_ARG"
  TOKEN_KIND="agent-token"
  echo "  Аутентификация: agent-token"
elif [[ -n "$USERNAME" && -n "$PASSWORD" ]]; then
  login
  TOKEN_KIND="jwt"
else
  echo "ERROR: Нужен либо --token, либо --username + --password"
  exit 2
fi

register_node

echo ""
echo "Сбор метрик запущен. Ctrl+C для остановки."
echo ""

i=0
while true; do
  metrics_json=$(collect_metrics)
  body="{\"nodeId\":\"$NODE_ID\",\"metrics\":$metrics_json}"

  resp=$(curl -sf -X POST "$SERVER/api/v1/metrics/batch" \
              -H "Authorization: Bearer $TOKEN" \
              -H "Content-Type: application/json" \
              -d "$body" 2>&1) || resp="ERROR"

  i=$(( i + 1 ))
  cpu_val=$(echo "$metrics_json" | grep -o '"cpu.usage"[^}]*"value":[0-9.]*' | grep -o '"value":[0-9.]*' | cut -d: -f2)
  mem_val=$(echo "$metrics_json" | grep -o '"memory.percent"[^}]*"value":[0-9.]*' | grep -o '"value":[0-9.]*' | cut -d: -f2)
  printf "  [%4d] cpu=%s%%  mem=%s%%\n" "$i" "${cpu_val:-?}" "${mem_val:-?}"

  sleep "$(( INTERVAL - 1 ))"  # -1 т.к. сбор CPU уже занимает 1 сек
done
