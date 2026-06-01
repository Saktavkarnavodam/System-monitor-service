#!/usr/bin/env bash
# =============================================================================
# Установщик агента мониторинга для Linux: ставит python-агента как
# systemd-сервис с Restart=always. После установки агент:
#   - стартует автоматически при загрузке системы (systemctl enable)
#   - перезапускается systemd-ом при падении процесса
#   - читает токен и сервер из защищённого env-файла, не из CLI
#
# Запуск (через визард UI генерируется автоматически):
#   curl -fsSL <SERVER>/install/install-linux.sh \
#     | sudo bash -s -- --server <SERVER> --token <TOKEN> [--node-name <NAME>]
#
# Удаление:
#   sudo systemctl disable --now monitoring-agent
#   sudo rm -rf /opt/monitoring-agent /etc/monitoring-agent /etc/systemd/system/monitoring-agent.service
#   sudo systemctl daemon-reload
# =============================================================================
set -euo pipefail

SERVER=""
TOKEN=""
NODE_NAME=""
INTERVAL="10"

while [[ $# -gt 0 ]]; do
  case $1 in
    --server)    SERVER="$2";    shift 2 ;;
    --token)     TOKEN="$2";     shift 2 ;;
    --node-name) NODE_NAME="$2"; shift 2 ;;
    --interval)  INTERVAL="$2";  shift 2 ;;
    *) echo "Неизвестный аргумент: $1"; exit 1 ;;
  esac
done

if [[ -z "$SERVER" || -z "$TOKEN" ]]; then
  echo "ERROR: нужны --server и --token" >&2
  exit 2
fi

# Требуем root: пишем systemd unit и /etc/, без root не получится.
if [[ "$EUID" -ne 0 ]]; then
  echo "ERROR: запусти через sudo (нужен root для systemd-юнита и /etc/monitoring-agent)" >&2
  exit 2
fi

# Зависимости
command -v python3 >/dev/null 2>&1 || { echo "ERROR: нужен python3" >&2; exit 3; }
command -v curl    >/dev/null 2>&1 || { echo "ERROR: нужен curl" >&2; exit 3; }

INSTALL_DIR=/opt/monitoring-agent
CONF_DIR=/etc/monitoring-agent
SERVICE_NAME=monitoring-agent
UNIT_FILE=/etc/systemd/system/${SERVICE_NAME}.service

echo "=== Установка monitoring-agent ==="
echo "  Сервер:  $SERVER"
echo "  Узел:    ${NODE_NAME:-<hostname>}"
echo "  Каталог: $INSTALL_DIR"
echo

# 1. Каталоги
install -d -m 755 "$INSTALL_DIR"
install -d -m 750 "$CONF_DIR"

# 2. Скачиваем python-агента
echo "  [1/5] Скачиваем agent.py..."
curl -fsSL "$SERVER/install/agent.py" -o "$INSTALL_DIR/agent.py"
chmod 755 "$INSTALL_DIR/agent.py"

# 3. psutil — пробуем системный путь, потом pip
echo "  [2/5] Проверяем psutil..."
if ! python3 -c "import psutil" 2>/dev/null; then
  echo "        ставим psutil через pip (--break-system-packages для PEP 668)..."
  python3 -m pip install --break-system-packages psutil >/dev/null 2>&1 \
    || python3 -m pip install psutil >/dev/null
fi
python3 -c "import psutil; print('        psutil', psutil.__version__)"

# 4. Защищённый env-файл с токеном
echo "  [3/5] Пишем $CONF_DIR/agent.env (0600)..."
umask 077
{
  echo "# Сгенерировано install-linux.sh. Менять руками можно, но при переустановке файл перезапишется."
  echo "MONITORING_SERVER=$SERVER"
  echo "MONITORING_TOKEN=$TOKEN"
  [[ -n "$NODE_NAME" ]] && echo "MONITORING_NODE_NAME=$NODE_NAME"
  echo "MONITORING_INTERVAL=$INTERVAL"
} > "$CONF_DIR/agent.env"
chmod 600 "$CONF_DIR/agent.env"

# 5. systemd unit
echo "  [4/5] Пишем $UNIT_FILE..."
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=Distributed Monitoring Agent
Documentation=$SERVER
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=$CONF_DIR/agent.env
ExecStart=/usr/bin/env python3 $INSTALL_DIR/agent.py --env-file $CONF_DIR/agent.env
Restart=always
RestartSec=10
# graceful-shutdown
KillMode=process
KillSignal=SIGTERM
TimeoutStopSec=15
# базовая изоляция (необязательно, но best-practice для systemd unit-ов)
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# 6. enable + start
echo "  [5/5] systemctl daemon-reload + enable --now..."
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME"

echo
echo "=== Готово ==="
echo "  Статус:  systemctl status $SERVICE_NAME --no-pager"
echo "  Логи:    journalctl -u $SERVICE_NAME -f"
echo
systemctl status "$SERVICE_NAME" --no-pager --lines=5 || true
