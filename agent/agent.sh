#!/usr/bin/env bash
# Агент мониторинга для Linux (читает /proc, df, ps; нужны curl и bc).
# Запуск:
#   ./agent.sh --server http://my-server:8080 --token agt_xxx --node-name web-01
set -euo pipefail

SERVER="http://localhost:8080"
TOKEN=""
NODE_NAME="$(hostname)"
HOST_ADDR="$(hostname)"
PORT=0
INTERVAL=10

while [[ $# -gt 0 ]]; do
  case $1 in
    --server)    SERVER="$2";    shift 2 ;;
    --token)     TOKEN="$2";     shift 2 ;;
    --node-name) NODE_NAME="$2"; shift 2 ;;
    --host)      HOST_ADDR="$2"; shift 2 ;;
    --port)      PORT="$2";      shift 2 ;;
    --interval)  INTERVAL="$2";  shift 2 ;;
    *) echo "Неизвестный аргумент: $1"; exit 1 ;;
  esac
done

[[ -z "$TOKEN" ]] && { echo "ERROR: --token обязателен"; exit 2; }
[[ "$TOKEN" != agt_* ]] && echo "WARN: --token обычно начинается с 'agt_'"

# ── HTTP-хелперы ──────────────────────────────────────────────────────────
api() {
  local method="$1" path="$2" body="${3-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$SERVER$path" \
         -H "Authorization: Bearer $TOKEN" \
         -H "Content-Type: application/json" \
         -d "$body"
  else
    curl -fsS -X "$method" "$SERVER$path" \
         -H "Authorization: Bearer $TOKEN"
  fi
}

# Ищем узел с таким именем; если нет — создаём.
register_node() {
  local existing
  existing=$(api GET "/api/v1/nodes")
  local id
  id=$(echo "$existing" | grep -oE "\"id\":\"[^\"]+\",\"[^,]*\"name\":\"$NODE_NAME\"" \
       | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)
  if [[ -n "$id" ]]; then
    echo "  Узел найден: $NODE_NAME ($id)"
    NODE_ID="$id"
    return
  fi
  echo -n "  Регистрируем: $NODE_NAME... "
  local body resp
  body="{\"name\":\"$NODE_NAME\",\"host\":\"$HOST_ADDR\",\"port\":$PORT,\"type\":\"server\",\"tags\":{\"os\":\"linux\"}}"
  resp=$(api POST "/api/v1/nodes" "$body")
  NODE_ID=$(echo "$resp" | grep -oE '"id":"[^"]+"' | head -1 | cut -d'"' -f4)
  echo "OK ($NODE_ID)"
}

# ── Метрики ───────────────────────────────────────────────────────────────
collect_metrics() {
  # CPU: дельта user+system+... к idle между двумя замерами /proc/stat (1 сек).
  local u1 n1 s1 i1 io1 q1 sq1 u2 n2 s2 i2 io2 q2 sq2 dt di cpu
  read -r _ u1 n1 s1 i1 io1 q1 sq1 _ < /proc/stat
  sleep 1
  read -r _ u2 n2 s2 i2 io2 q2 sq2 _ < /proc/stat
  dt=$(( (u2+n2+s2+i2+io2+q2+sq2) - (u1+n1+s1+i1+io1+q1+sq1) ))
  di=$(( i2 - i1 ))
  cpu=0
  [[ $dt -gt 0 ]] && cpu=$(echo "scale=1; (1 - $di / $dt) * 100" | bc)
  local cores
  cores=$(nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo)

  # RAM
  local mem_total mem_avail mem_used mem_pct
  mem_total=$(awk '/^MemTotal:/  { print $2 }' /proc/meminfo)
  mem_avail=$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)
  mem_used=$(( mem_total - mem_avail ))
  mem_pct=$(echo "scale=1; $mem_used / $mem_total * 100" | bc)
  local mem_used_mb mem_total_mb
  mem_used_mb=$(echo "scale=1; $mem_used / 1024" | bc)
  mem_total_mb=$(echo "scale=1; $mem_total / 1024" | bc)

  # Диск (корневой раздел)
  local disk_used disk_total disk_pct
  read -r _ disk_total disk_used _ disk_pct _ < <(df -BG / | tail -1)
  disk_total=${disk_total%G}; disk_used=${disk_used%G}; disk_pct=${disk_pct%\%}

  # Процессы
  local procs
  procs=$(ps -e --no-headers 2>/dev/null | wc -l)

  cat <<EOF
[
  {"name":"cpu.usage","value":$cpu,"unit":"percent","type":"GAUGE"},
  {"name":"cpu.count_logical","value":$cores,"unit":"cores","type":"GAUGE"},
  {"name":"memory.used_mb","value":$mem_used_mb,"unit":"MB","type":"GAUGE"},
  {"name":"memory.total_mb","value":$mem_total_mb,"unit":"MB","type":"GAUGE"},
  {"name":"memory.percent","value":$mem_pct,"unit":"percent","type":"GAUGE"},
  {"name":"disk.used_gb","value":$disk_used,"unit":"GB","type":"GAUGE"},
  {"name":"disk.total_gb","value":$disk_total,"unit":"GB","type":"GAUGE"},
  {"name":"disk.percent","value":$disk_pct,"unit":"percent","type":"GAUGE"},
  {"name":"process.count","value":$procs,"unit":"count","type":"GAUGE"}
]
EOF
}

# ── Main ──────────────────────────────────────────────────────────────────
echo "=== Агент мониторинга (Bash/Linux) ==="
echo "  Сервер:   $SERVER"
echo "  Узел:     $NODE_NAME ($HOST_ADDR)"
echo "  Интервал: $INTERVAL сек"
echo

NODE_ID=""
register_node
echo

i=0
while true; do
  metrics=$(collect_metrics)
  body="{\"nodeId\":\"$NODE_ID\",\"metrics\":$metrics}"
  api POST "/api/v1/metrics/batch" "$body" > /dev/null || echo "  [ERR] ingest failed"

  i=$(( i + 1 ))
  cpu=$(echo "$metrics" | grep -oE '"cpu.usage"[^}]*"value":[0-9.]+' | grep -oE '[0-9.]+$')
  mem=$(echo "$metrics" | grep -oE '"memory.percent"[^}]*"value":[0-9.]+' | grep -oE '[0-9.]+$')
  printf "  [%4d] cpu=%s%%  mem=%s%%\n" "$i" "${cpu:-?}" "${mem:-?}"

  # collect_metrics уже включает 1-секундный замер CPU
  sleep "$(( INTERVAL > 1 ? INTERVAL - 1 : 0 ))"
done
