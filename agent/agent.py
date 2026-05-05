#!/usr/bin/env python3
"""
Агент мониторинга — собирает реальные метрики машины и шлёт в сервер.

Требования:
    pip install psutil

Использование (рекомендованный путь — agent-токен):
    python agent.py --server http://localhost:8080 --token agt_xxx --node-name my-laptop

Старый путь (логин/пароль) — оставлен для обратной совместимости:
    python agent.py --server http://localhost:8080 --username admin --password admin123 --node-name my-server

Что собирает:
    cpu.usage          — загрузка CPU в %
    memory.used_mb     — использованная RAM в MB
    memory.percent     — использованная RAM в %
    disk.used_gb       — использованный диск (/) в GB
    disk.percent       — использованный диск в %
    net.bytes_sent_ps  — байт/сек отправлено (сетевой интерфейс)
    net.bytes_recv_ps  — байт/сек получено
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error

try:
    import psutil
except ImportError:
    print("ERROR: Установите psutil:  pip install psutil")
    sys.exit(1)


# ── HTTP-хелперы (без сторонних libs, только urllib) ─────────────────────────

def http_post(url, data, token=None):
    body = json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, method='POST')
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors='replace')
        raise RuntimeError(f"HTTP {e.code}: {body}")


def http_get(url, token=None):
    req = urllib.request.Request(url)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


# ── Auth ──────────────────────────────────────────────────────────────────────

def login(server, username, password):
    """Старый путь: логин/пароль -> JWT. Используется только если --token не передан."""
    print(f"  Вход как {username}...", end=' ')
    try:
        res = http_post(f'{server}/api/v1/auth/login',
                        {'username': username, 'password': password})
        print("OK")
        return res['token']
    except Exception:
        pass

    print("нет аккаунта — регистрируемся...", end=' ')
    res = http_post(f'{server}/api/v1/auth/register',
                    {'username': username, 'password': password})
    print("OK")
    return res['token']


# ── Node registration ─────────────────────────────────────────────────────────

def get_or_register_node(server, token, node_name, host, port):
    """Возвращает id существующего узла с таким именем или регистрирует новый."""
    nodes = http_get(f'{server}/api/v1/nodes', token)
    for n in nodes:
        if n['name'] == node_name:
            node_id = n['id']
            print(f"  Найден существующий узел: {node_name} ({node_id})")
            return node_id

    print(f"  Регистрируем новый узел: {node_name}...", end=' ')
    n = http_post(f'{server}/api/v1/nodes', {
        'name': node_name,
        'host': host,
        'port': port,
        'type': 'server',
        'tags': {'os': sys.platform}
    }, token)
    print(f"OK ({n['id']})")
    return n['id']


# ── Metrics collection ────────────────────────────────────────────────────────

_prev_net = None

def collect_metrics():
    global _prev_net

    metrics = []

    # CPU — процент за 1-секундное измерение
    cpu_pct = psutil.cpu_percent(interval=1)
    metrics.append({'name': 'cpu.usage',        'value': cpu_pct,            'unit': 'percent', 'type': 'GAUGE'})
    metrics.append({'name': 'cpu.count_logical', 'value': psutil.cpu_count(), 'unit': 'cores',   'type': 'GAUGE'})

    # RAM
    mem = psutil.virtual_memory()
    metrics.append({'name': 'memory.used_mb',  'value': round(mem.used / 1024**2, 1), 'unit': 'MB',      'type': 'GAUGE'})
    metrics.append({'name': 'memory.percent',  'value': mem.percent,                  'unit': 'percent', 'type': 'GAUGE'})
    metrics.append({'name': 'memory.total_mb', 'value': round(mem.total / 1024**2, 1),'unit': 'MB',      'type': 'GAUGE'})

    # Диск (корневой раздел на Linux / C: на Windows)
    try:
        disk_path = 'C:\\' if sys.platform == 'win32' else '/'
        disk = psutil.disk_usage(disk_path)
        metrics.append({'name': 'disk.used_gb',  'value': round(disk.used  / 1024**3, 2), 'unit': 'GB',      'type': 'GAUGE'})
        metrics.append({'name': 'disk.total_gb', 'value': round(disk.total / 1024**3, 2), 'unit': 'GB',      'type': 'GAUGE'})
        metrics.append({'name': 'disk.percent',  'value': disk.percent,                   'unit': 'percent', 'type': 'GAUGE'})
    except Exception as e:
        print(f"  [warn] disk: {e}")

    # Сеть — байт/сек (дельта от предыдущего измерения)
    net_now = psutil.net_io_counters()
    if _prev_net is not None:
        dt = 1  # измерения раз в interval (cpu_percent уже дал 1 сек выше)
        sent_ps = max(0, net_now.bytes_sent - _prev_net.bytes_sent) / dt
        recv_ps = max(0, net_now.bytes_recv - _prev_net.bytes_recv) / dt
        metrics.append({'name': 'net.bytes_sent_ps', 'value': round(sent_ps), 'unit': 'bytes/s', 'type': 'GAUGE'})
        metrics.append({'name': 'net.bytes_recv_ps', 'value': round(recv_ps), 'unit': 'bytes/s', 'type': 'GAUGE'})
    _prev_net = net_now

    # Кол-во процессов
    metrics.append({'name': 'process.count', 'value': len(psutil.pids()), 'unit': 'count', 'type': 'GAUGE'})

    return metrics


# ── Main loop ─────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description='Агент мониторинга для distributed-monitoring')
    p.add_argument('--server',    default='http://localhost:8080', help='URL сервера мониторинга')
    p.add_argument('--token',     default=None,                    help='Agent-токен (рекомендуется). Если задан, --username/--password игнорируются.')
    p.add_argument('--username',  default=None,                    help='Имя пользователя (только если --token не задан)')
    p.add_argument('--password',  default=None,                    help='Пароль (только если --token не задан)')
    p.add_argument('--node-name', default=None,                    help='Имя узла (по умолчанию — hostname)')
    p.add_argument('--host',      default=None,                    help='Адрес этой машины (как её видит сервер)')
    p.add_argument('--port',      type=int, default=0,             help='Порт этой машины (необязательно)')
    p.add_argument('--interval',  type=int, default=10,            help='Интервал отправки метрик (сек)')
    args = p.parse_args()

    import socket
    hostname = socket.gethostname()
    node_name = args.node_name or hostname
    host = args.host or hostname

    print(f"=== Агент мониторинга ===")
    print(f"  Сервер:   {args.server}")
    print(f"  Узел:     {node_name} ({host})")
    print(f"  Интервал: {args.interval} сек")
    print()

    # === Аутентификация ==========================================
    # Приоритет: agent-токен > username/password
    if args.token:
        if not args.token.startswith('agt_'):
            print("WARN: --token обычно начинается с 'agt_'. Возможно, вы передали JWT?")
        token = args.token
        token_kind = 'agent-token'
    elif args.username and args.password:
        token = login(args.server, args.username, args.password)
        token_kind = 'jwt'
    else:
        print("ERROR: Нужен либо --token, либо --username + --password")
        sys.exit(2)
    print(f"  Аутентификация: {token_kind}\n")

    node_id = get_or_register_node(args.server, token, node_name, host, args.port)

    print(f"\nСбор метрик запущен. Ctrl+C для остановки.\n")

    iteration = 0
    while True:
        try:
            metrics = collect_metrics()
            result = http_post(
                f'{args.server}/api/v1/metrics/batch',
                {'nodeId': node_id, 'metrics': metrics},
                token
            )
            iteration += 1
            cpu_val = next((m['value'] for m in metrics if m['name'] == 'cpu.usage'), '?')
            mem_val = next((m['value'] for m in metrics if m['name'] == 'memory.percent'), '?')
            print(f"  [{iteration:4d}] cpu={cpu_val}%  mem={mem_val}%  метрик={result.get('accepted', '?')}", flush=True)
        except KeyboardInterrupt:
            print("\nОстановка агента.")
            break
        except Exception as e:
            print(f"  [ERR] {e}", flush=True)
            # Если использовали логин/пароль и поймали 401 — обновляем JWT
            if token_kind == 'jwt' and '401' in str(e):
                try:
                    token = login(args.server, args.username, args.password)
                except Exception:
                    pass
            # Agent-токен сам не обновляется — если 401, значит, он отозван;
            # перезапускать перевыпуск нечем, продолжаем попытки

        time.sleep(max(0, args.interval - 1))  # -1 т.к. cpu_percent(interval=1) ждёт 1 сек


if __name__ == '__main__':
    main()
