#!/usr/bin/env python3
"""
Агент мониторинга для Linux/macOS/Windows. Шлёт метрики на сервер
через POST /api/v1/metrics/batch с agent-токеном в Authorization.
Установка psutil:  pip install --user psutil

Источник параметров (по приоритету): CLI-аргументы > env-файл > переменные среды.
Env-файл (формат KEY=VALUE) задаётся через --env-file и используется install-скриптами,
которые ставят агента как systemd-сервис (Linux) / Scheduled Task (Windows).
"""
import argparse
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

try:
    import psutil
except ImportError:
    sys.exit("ERROR: установите psutil:  pip install --user psutil")


def http_json(method, url, token, data=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header('Authorization', 'Bearer ' + token)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code}: {e.read().decode(errors='replace')}")


def get_or_register_node(server, token, name, host, port):
    """Возвращает id узла с таким именем; создаёт новый, если не нашёл."""
    for n in http_json('GET', f'{server}/api/v1/nodes', token):
        if n['name'] == name:
            return n['id']
    node = http_json('POST', f'{server}/api/v1/nodes', token, {
        'name': name, 'host': host, 'port': port,
        'type': 'server', 'tags': {'os': sys.platform},
    })
    return node['id']


_prev_net = None


def collect_metrics():
    """Снимок системных метрик. cpu_percent блокирует на 1 секунду."""
    global _prev_net
    out = []

    cpu = psutil.cpu_percent(interval=1)
    out.append(('cpu.usage',         cpu,                 'percent'))
    out.append(('cpu.count_logical', psutil.cpu_count(),  'cores'))

    mem = psutil.virtual_memory()
    out.append(('memory.used_mb',  round(mem.used  / 1024**2, 1), 'MB'))
    out.append(('memory.total_mb', round(mem.total / 1024**2, 1), 'MB'))
    out.append(('memory.percent',  mem.percent,                   'percent'))

    try:
        disk_path = 'C:\\' if sys.platform == 'win32' else '/'
        disk = psutil.disk_usage(disk_path)
        out.append(('disk.used_gb',  round(disk.used  / 1024**3, 2), 'GB'))
        out.append(('disk.total_gb', round(disk.total / 1024**3, 2), 'GB'))
        out.append(('disk.percent',  disk.percent,                   'percent'))
    except OSError as e:
        print(f"  [warn] disk: {e}")

    # net.* — байты/сек считаются дельтой от предыдущего замера.
    net = psutil.net_io_counters()
    if _prev_net is not None:
        out.append(('net.bytes_sent_ps', max(0, net.bytes_sent - _prev_net.bytes_sent), 'bytes/s'))
        out.append(('net.bytes_recv_ps', max(0, net.bytes_recv - _prev_net.bytes_recv), 'bytes/s'))
    _prev_net = net

    out.append(('process.count', len(psutil.pids()), 'count'))

    return [{'name': n, 'value': v, 'unit': u, 'type': 'GAUGE'} for n, v, u in out]


def load_env_file(path):
    """Лёгкий парсер KEY=VALUE-файла (комментарии и пустые строки игнорируются).
    Используется install-скриптами, которые пишут /etc/monitoring-agent/agent.env
    или %ProgramData%\monitoring-agent\agent.env. Уже выставленные env-переменные
    не перезаписываем — CLI/runtime-окружение имеет приоритет."""
    if not path or not os.path.exists(path):
        return
    with open(path, 'r', encoding='utf-8') as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            if '=' not in line:
                continue
            k, v = line.split('=', 1)
            k = k.strip()
            v = v.strip().strip('"').strip("'")
            os.environ.setdefault(k, v)


def main():
    # --env-file разбираем ДО argparse, чтобы env-переменные из файла стали дефолтами.
    if '--env-file' in sys.argv:
        i = sys.argv.index('--env-file')
        if i + 1 < len(sys.argv):
            load_env_file(sys.argv[i + 1])

    p = argparse.ArgumentParser(description='Агент мониторинга для distributed-monitoring')
    p.add_argument('--server',    default=os.environ.get('MONITORING_SERVER', 'http://localhost:8080'))
    p.add_argument('--token',     default=os.environ.get('MONITORING_TOKEN', ''),
                   help='Agent-токен (формат agt_…). Можно задать MONITORING_TOKEN в env.')
    p.add_argument('--node-name', default=os.environ.get('MONITORING_NODE_NAME'),
                   help='Имя узла (по умолчанию hostname)')
    p.add_argument('--host',      default=os.environ.get('MONITORING_HOST'),
                   help='Адрес этой машины')
    p.add_argument('--port',      type=int, default=int(os.environ.get('MONITORING_PORT', '0') or 0))
    p.add_argument('--interval',  type=int, default=int(os.environ.get('MONITORING_INTERVAL', '10') or 10))
    p.add_argument('--env-file',  default=None,
                   help='Путь к KEY=VALUE-файлу с параметрами (используется install-скриптами)')
    args = p.parse_args()

    if not args.token:
        sys.exit("ERROR: --token не задан (или переменная MONITORING_TOKEN). Без него агент не сможет авторизоваться.")

    if not args.token.startswith('agt_'):
        print("WARN: --token обычно начинается с 'agt_'")

    hostname  = socket.gethostname()
    node_name = args.node_name or hostname
    host      = args.host      or hostname

    print(f"=== Агент мониторинга ===")
    print(f"  Сервер:   {args.server}")
    print(f"  Узел:     {node_name} ({host})")
    print(f"  Интервал: {args.interval} сек")

    node_id = get_or_register_node(args.server, args.token, node_name, host, args.port)
    print(f"  Узел id:  {node_id}\n")

    iteration = 0
    while True:
        try:
            metrics = collect_metrics()
            try:
                result = http_json('POST', f'{args.server}/api/v1/metrics/batch',
                                   args.token, {'nodeId': node_id, 'metrics': metrics})
            except RuntimeError as e:
                # Узел удалили на сервере (404) — перерегистрируемся и повторяем (self-heal),
                # чтобы агент не залипал навсегда на удалённом node_id.
                if 'HTTP 404' in str(e):
                    print("  [warn] узел не найден на сервере — перерегистрирую...", flush=True)
                    node_id = get_or_register_node(args.server, args.token, node_name, host, args.port)
                    result = http_json('POST', f'{args.server}/api/v1/metrics/batch',
                                       args.token, {'nodeId': node_id, 'metrics': metrics})
                else:
                    raise
            iteration += 1
            cpu = next((m['value'] for m in metrics if m['name'] == 'cpu.usage'),     '?')
            mem = next((m['value'] for m in metrics if m['name'] == 'memory.percent'), '?')
            print(f"  [{iteration:4d}] cpu={cpu}% mem={mem}% accepted={result.get('accepted', '?')}",
                  flush=True)
        except KeyboardInterrupt:
            print("\nОстановка."); break
        except Exception as e:
            print(f"  [ERR] {e}", flush=True)
        # interval-1, т.к. cpu_percent уже блокирует на 1 сек
        time.sleep(max(0, args.interval - 1))


if __name__ == '__main__':
    main()
