// Модал узла: переключатели окна/бакета, сетка графиков Chart.js,
// произвольный временной интервал.

const nm = {
  node: null,
  windowSeconds: 3600,        // длина окна; 0 при custom-режиме
  bucketSeconds: 60,
  customFrom: null,            // ISO строки для custom-режима
  customTo:   null,
  charts: {},                  // metricName -> Chart.js instance
  hiddenCharts: new Set(),     // имена метрик, скрытых пользователем (на время сессии модалки)
  refreshTimer: null,
};

function openNodeModal(node) {
  nm.node = node;
  document.getElementById('nodeModal').classList.add('open');
  applyNodeSnapshot(node);
  document.getElementById('nmProbeResult').textContent = '';

  destroyCharts();
  document.getElementById('nmCharts').innerHTML = '';
  nm.hiddenCharts = new Set();
  updateHiddenChartsButton();
  loadCharts();

  if (nm.refreshTimer) clearInterval(nm.refreshTimer);
  nm.refreshTimer = setInterval(() => {
    if (!document.getElementById('nmAutoRefresh').checked) return;
    // Перетягиваем свежий снимок узла, чтобы heartbeat-возраст и статус не
    // протухали — без этого «секунд без ответа» растёт линейно с момента
    // открытия модалки, даже когда агент исправно шлёт метрики.
    refreshNodeSnapshot();
    // Графики — только в режиме скользящего окна; в custom-режиме окно
    // фиксированное, авто-перерисовка не имеет смысла.
    if (nm.windowSeconds > 0) loadCharts(true);
  }, 5000);
}

/**
 * Подтянуть свежий объект узла с сервера и обновить шапку модалки +
 * health-строку. Молча игнорирует ошибки сети — пользователь увидит
 * следующий тик через 5 секунд.
 */
async function refreshNodeSnapshot() {
  if (!nm.node || !nm.node.id) return;
  try {
    const fresh = await api(`/api/v1/nodes/${nm.node.id}`);
    if (fresh) {
      nm.node = fresh;
      applyNodeSnapshot(fresh);
    }
  } catch { /* следующий тик попробует снова */ }
}

/** Отрисовка шапки модалки + health-строки из переданного снимка узла. */
function applyNodeSnapshot(node) {
  document.getElementById('nmName').textContent = node.name;
  document.getElementById('nmMeta').innerHTML = [
    escapeHtml(node.type || 'service'),
    escapeHtml(node.host || '—'),
    `<span class="status-${node.status}">${node.status}</span>`,
    `heartbeat: ${fmt(node.lastHeartbeat)}`,
    `id: <span class="muted">${node.id}</span>`,
  ].join(' · ');
  renderHealthLine(node);
  renderServicesPanel(node);
}

// ============================================================================
// Variant B: блок «Сервисы на узле»
// ============================================================================

/** Рендерит таблицу сервисов узла и адрес host над ней. */
function renderServicesPanel(node) {
  const hostEl = document.getElementById('nmHostText');
  if (hostEl) hostEl.textContent = node.host || '(не задан)';
  const tbody = document.getElementById('nmServicesTbody');
  if (!tbody) return;
  const services = node.services || [];
  if (services.length === 0) {
    tbody.innerHTML = '<tr><td colspan="4" class="muted" style="padding: 6px 8px;">Сервисов пока нет. Добавь, чтобы probe мог проверять TCP.</td></tr>';
    return;
  }
  tbody.innerHTML = '';
  services.forEach((s, idx) => {
    const tr = document.createElement('tr');
    tr.dataset.idx = idx;
    tr.innerHTML = `
      <td style="padding: 6px 8px;">${escapeHtml(s.name)}</td>
      <td style="padding: 6px 8px;"><span class="pill">${s.port}</span></td>
      <td style="padding: 6px 8px;" class="svc-status muted">—</td>
      <td style="padding: 6px 8px; text-align: right;">
        <button class="danger-btn" onclick="removeService(${idx})">×</button>
      </td>
    `;
    tbody.appendChild(tr);
  });
}

async function addService() {
  const errEl = document.getElementById('nmServicesErr');
  errEl.textContent = '';
  const nameEl = document.getElementById('nmNewSvcName');
  const portEl = document.getElementById('nmNewSvcPort');
  const name = nameEl.value.trim();
  const port = parseInt(portEl.value, 10);
  if (!port || port < 1 || port > 65535) {
    errEl.textContent = 'Порт должен быть числом 1–65535.';
    return;
  }
  const newServices = (nm.node.services || []).slice();
  newServices.push({ name: name || ('svc-' + port), port });
  try {
    const updated = await api(`/api/v1/nodes/${nm.node.id}`, {
      method: 'PATCH', body: JSON.stringify({ services: newServices }),
    });
    nm.node = updated;
    nameEl.value = ''; portEl.value = '';
    renderServicesPanel(updated);
  } catch (e) {
    errEl.textContent = 'Не удалось сохранить: ' + e.message;
  }
}

async function removeService(idx) {
  const errEl = document.getElementById('nmServicesErr');
  errEl.textContent = '';
  const cur = (nm.node.services || []).slice();
  if (idx < 0 || idx >= cur.length) return;
  if (!confirm(`Удалить сервис «${cur[idx].name}:${cur[idx].port}»?`)) return;
  cur.splice(idx, 1);
  try {
    const updated = await api(`/api/v1/nodes/${nm.node.id}`, {
      method: 'PATCH', body: JSON.stringify({ services: cur }),
    });
    nm.node = updated;
    renderServicesPanel(updated);
  } catch (e) {
    errEl.textContent = 'Не удалось удалить: ' + e.message;
  }
}

async function editNodeHost() {
  const cur = nm.node.host || '';
  const next = prompt('Host этого узла (адрес машины — IP или DNS):', cur);
  if (next == null) return; // отмена
  try {
    const updated = await api(`/api/v1/nodes/${nm.node.id}`, {
      method: 'PATCH', body: JSON.stringify({ host: next.trim() }),
    });
    nm.node = updated;
    applyNodeSnapshot(updated);
  } catch (e) {
    alert('Не удалось обновить host: ' + e.message);
  }
}

/** Прозвонка всех сервисов (кнопка в панели «Сервисы»): POST /probe-services,
 *  обновляет статус в таблице. Возвращает отчёт или null (нет сервисов / host
 *  не задан / запрос упал). */
async function probeServicesNow() {
  const errEl = document.getElementById('nmServicesErr');
  errEl.textContent = '';
  const services = nm.node.services || [];
  if (services.length === 0) {
    errEl.textContent = 'Сначала добавь хотя бы один сервис.';
    return null;
  }
  if (!nm.node.host) {
    errEl.textContent = 'Не задан host узла — нечего прозванивать. Нажми «изменить» рядом с host.';
    return null;
  }
  markServiceRowsProbing();
  let report;
  try {
    report = await api(`/api/v1/nodes/${nm.node.id}/probe-services`, { method: 'POST' });
  } catch (e) {
    errEl.textContent = 'Probe не сработал: ' + e.message;
    return null;
  }
  renderServiceProbeResults(report);
  return report;
}

/** Помечает колонку статуса всех строк таблицы сервисов как «проверяем…». */
function markServiceRowsProbing() {
  document.querySelectorAll('#nmServicesTbody .svc-status').forEach(td => {
    td.innerHTML = '<span style="color:#94a3b8">проверяем…</span>';
  });
}

/** Раскрашивает колонку статуса в таблице сервисов по отчёту /probe-services. */
function renderServiceProbeResults(report) {
  const services = nm.node.services || [];
  const byPort = {};
  (report.services || []).forEach(p => { byPort[p.port] = p; });
  document.querySelectorAll('#nmServicesTbody tr').forEach(tr => {
    const idx = parseInt(tr.dataset.idx, 10);
    if (isNaN(idx)) return;
    const svc = services[idx];
    const probe = byPort[svc.port];
    const td = tr.querySelector('.svc-status');
    if (!probe) {
      td.innerHTML = '<span style="color:#94a3b8">—</span>';
    } else if (probe.reachable) {
      td.innerHTML = `<span style="color:#22c55e;">✓ reachable</span> <span class="muted">(${probe.latencyMs} мс)</span>`;
    } else {
      td.innerHTML = `<span style="color:#ef4444;">✗ ${escapeHtml(probe.error || 'unreachable')}</span>`;
    }
  });
}

// Краткий статус — что мы знаем по heartbeat без probe.
function renderHealthLine(node) {
  const el = document.getElementById('nmHealthLine');
  if (!el) return;
  if (!node.lastHeartbeat) {
    el.innerHTML = '<span style="color:#94a3b8">Heartbeat ещё не приходил.</span>';
    return;
  }
  const ageMs = Date.now() - new Date(node.lastHeartbeat).getTime();
  const ageS  = Math.round(ageMs / 1000);
  const ageH  = humanAge(ageS);
  if (ageS <= 15) {
    el.innerHTML = `<span style="color:#22c55e">✓</span> Агент шлёт метрики (heartbeat ${ageH} назад).`;
  } else if (ageS <= 30) {
    el.innerHTML = `<span style="color:#f59e0b">⚠</span> Heartbeat запаздывает (${ageH} назад). Нажми «Проверить», чтобы понять причину.`;
  } else {
    el.innerHTML = `<span style="color:#ef4444">✗</span> Heartbeat <b>${ageH}</b> назад — агент молчит. Нажми «Проверить», чтобы понять, упал агент или узел.`;
  }
}

function humanAge(seconds) {
  if (seconds < 60)    return seconds + ' сек';
  if (seconds < 3600)  return Math.floor(seconds / 60) + ' мин';
  if (seconds < 86400) return Math.floor(seconds / 3600) + ' ч';
  return Math.floor(seconds / 86400) + ' д';
}

/**
 * Главная кнопка «Проверить узел». Variant B:
 *  - вызывает /probe-services (массив результатов по каждому сервису),
 *  - формирует обобщённый вердикт по heartbeat-возрасту + TCP-результатам,
 *  - параллельно обновляет статусы в таблице сервисов.
 *
 * Логика вердиктов:
 *  - heartbeat свежий + сервисы все ok → всё штатно.
 *  - heartbeat свежий + часть сервисов unreachable → агент жив, какой-то
 *    из мониторимых сервисов на узле упал/недоступен.
 *  - heartbeat устарел + хотя бы один TCP-ответ есть → AGENT DOWN
 *    (машина жива, агент молчит).
 *  - heartbeat устарел + всё TCP не отвечает → NODE DOWN.
 *  - heartbeat устарел и нет сервисов для probe → не различить, показываем
 *    нейтральный «нет данных».
 */
async function probeCurrentNode() {
  if (!nm.node) return;
  const out = document.getElementById('nmProbeResult');
  out.innerHTML = '<span style="color:#94a3b8">Проверяем сервисы…</span>';

  // прежде чем спрашивать сервер, актуализируем сам узел (вдруг heartbeat пришёл)
  await refreshNodeSnapshot();

  // Один probe-services на всё: обновляем и таблицу сервисов, и строим из
  // этого же отчёта обобщённый вердикт ниже — без повторного TCP-прозвона.
  markServiceRowsProbing();
  let report;
  try {
    report = await api(`/api/v1/nodes/${nm.node.id}/probe-services`, { method: 'POST' });
  } catch (e) {
    out.innerHTML = `<span style="color:#ef4444">Ошибка probe: ${escapeHtml(e.message)}</span>`;
    return;
  }
  renderServiceProbeResults(report);

  const ageS = report.heartbeatAgeSeconds;
  const hbFresh = ageS != null && ageS <= 15;
  const hbStale = ageS == null || ageS > 30;
  const probes = report.services || [];
  const okCount = probes.filter(p => p.reachable).length;
  const failCount = probes.length - okCount;
  const noProbes = probes.length === 0;

  let verdict, color, hint;
  if (hbFresh && (noProbes || failCount === 0)) {
    color = '#22c55e';
    verdict = noProbes
      ? `Всё штатно: heartbeat свежий (${humanAge(ageS)} назад), сервисов нет — TCP-проверка не нужна.`
      : `Всё штатно: heartbeat свежий, все ${probes.length} сервисов отвечают.`;
    hint = noProbes
      ? 'Если на узле есть сервис, который ты хочешь проверять (web/db/...) — добавь его в таблицу ниже.'
      : 'Агент жив, сервисы доступны.';
  } else if (hbFresh && failCount > 0) {
    color = '#f59e0b';
    verdict = `Агент жив, но ${failCount} из ${probes.length} сервисов недоступны.`;
    hint = 'Метрики собираются нормально — проблема в самих сервисах на этом узле. Смотри статус в таблице ниже.';
  } else if (hbStale && !noProbes && okCount > 0) {
    color = '#f59e0b';
    verdict = `AGENT DOWN: heartbeat ${humanAge(ageS)} назад, но узел отвечает по TCP (${okCount} из ${probes.length} сервисов).`;
    hint =
      'Машина жива, но процесс агента молчит. Сервис настроен на автоперезапуск (Restart=always), обычно поднимается за 10–20 секунд. Если не появится — зайди на машину и выполни:<br/>' +
      '&nbsp;&nbsp;<code>sudo systemctl restart monitoring-agent</code> &nbsp;(Linux)<br/>' +
      '&nbsp;&nbsp;<code>Restart-ScheduledTask -TaskName monitoring-agent</code> &nbsp;(Windows)';
  } else if (hbStale && !noProbes && okCount === 0) {
    color = '#ef4444';
    verdict = `NODE DOWN: heartbeat ${humanAge(ageS)} назад, ни один сервис не отвечает по TCP.`;
    hint = 'Скорее всего, машина упала или нет сети. После восстановления агент стартует автоматически — он системный сервис.';
  } else if (hbStale && noProbes) {
    color = '#94a3b8';
    verdict = `Heartbeat ${humanAge(ageS)} назад, сервисов для TCP-проверки нет.`;
    hint = 'Без сервисов нельзя отличить «упал агент» от «упал узел». Добавь хотя бы один сервис (любой открытый порт на узле — SSH :22, RDP :3389, твой сервис) и нажми «Проверить» снова.';
  } else {
    color = '#94a3b8';
    verdict = ageS == null ? 'Heartbeat ещё не приходил.' : `Heartbeat ${humanAge(ageS)} назад.`;
    hint = 'Жди — агент скоро отправит метрики.';
  }
  out.innerHTML = `<span style="color:${color}; font-weight:600;">${verdict}</span><br/><span style="color:#94a3b8;">${hint}</span>`;
  renderHealthLine(nm.node);
}

// Открывает визард с готовой install-командой для этого узла. Используется
// в редких случаях: агента нет совсем / переустановка с нуля / токен скомпрометирован.
function reinstallCurrentNode() {
  if (!nm.node) return;
  if (typeof openReconnectWizard === 'function') {
    openReconnectWizard(nm.node);
  } else {
    alert('Wizard не загружен.');
  }
}

function closeNodeModal() {
  document.getElementById('nodeModal').classList.remove('open');
  if (nm.refreshTimer) { clearInterval(nm.refreshTimer); nm.refreshTimer = null; }
  destroyCharts();
  nm.node = null;
}

function destroyCharts() {
  Object.values(nm.charts).forEach(c => c.destroy());
  nm.charts = {};
}

// ============================================================================
// Скрытие/показ графиков. Скрытый график не удаляется — только не отображается,
// чтобы не мешал. «Показать скрытые» возвращает все обратно. Состояние живёт
// до закрытия модалки узла (сбрасывается в openNodeModal).
// ============================================================================

/** Скрыть график по имени метрики (визуально). */
function hideChart(name) {
  nm.hiddenCharts.add(name);
  const card = document.getElementById('chart-card-' + cssId(name));
  if (card) card.style.display = 'none';
  updateHiddenChartsButton();
}

/** Вернуть все скрытые графики обратно. */
function showHiddenCharts() {
  nm.hiddenCharts.clear();
  document.querySelectorAll('#nmCharts .chart-card').forEach(c => { c.style.display = ''; });
  updateHiddenChartsButton();
}

/** Показывает/прячет кнопку «Показать скрытые (N)» и обновляет счётчик. */
function updateHiddenChartsButton() {
  const btn = document.getElementById('nmShowHidden');
  if (!btn) return;
  const n = nm.hiddenCharts.size;
  btn.style.display = n > 0 ? '' : 'none';
  if (n > 0) btn.textContent = `Показать скрытые (${n})`;
}

function setWindow(secs) {
  nm.windowSeconds = secs;
  nm.customFrom = nm.customTo = null;
  document.getElementById('nmCustomRange').style.display = 'none';
  document.querySelectorAll('.controls button[data-window]').forEach(b => {
    b.classList.toggle('active', Number(b.dataset.window) === secs);
  });
  loadCharts();
}

function setBucket(secs) {
  nm.bucketSeconds = secs;
  document.querySelectorAll('.controls button[data-bucket]').forEach(b => {
    b.classList.toggle('active', Number(b.dataset.bucket) === secs);
  });
  loadCharts();
}

function toggleCustomRange() {
  const box = document.getElementById('nmCustomRange');
  const isOpen = box.style.display === 'flex';
  if (isOpen) {
    box.style.display = 'none';
  } else {
    box.style.display = 'flex';
    // Префилл: последний выбранный диапазон или текущий час.
    const fromInput = document.getElementById('nmCustomFrom');
    const toInput   = document.getElementById('nmCustomTo');
    if (!fromInput.value) {
      const now = new Date();
      const from = new Date(now.getTime() - 3600 * 1000);
      fromInput.value = toLocalDatetime(from);
      toInput.value   = toLocalDatetime(now);
    }
  }
}

function applyCustomRange() {
  const fromVal = document.getElementById('nmCustomFrom').value;
  const toVal   = document.getElementById('nmCustomTo').value;
  if (!fromVal || !toVal) { alert('Выбери дату/время «от» и «до»'); return; }
  const from = new Date(fromVal);
  const to   = new Date(toVal);
  if (from >= to) { alert('«От» должно быть раньше «до»'); return; }

  nm.customFrom = from.toISOString();
  nm.customTo   = to.toISOString();
  nm.windowSeconds = 0; // признак custom-режима, отключает авто-обновление
  document.querySelectorAll('.controls button[data-window]').forEach(b => b.classList.remove('active'));
  loadCharts();
}

function toLocalDatetime(d) {
  // ISO-формат для <input type="datetime-local">: 'YYYY-MM-DDTHH:MM'
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function loadCharts(incremental = false) {
  if (!nm.node) return;
  const nodeId = nm.node.id;

  let latestArr;
  try {
    latestArr = await api(`/api/v1/nodes/${nodeId}/metrics/latest`);
  } catch (e) {
    console.error(e); return;
  }
  const latest = {};
  (latestArr || []).forEach(m => { if (m && m.name) latest[m.name] = m; });
  const metricNames = Object.keys(latest).sort();

  const wrap = document.getElementById('nmCharts');
  const empty = document.getElementById('nmEmpty');
  if (metricNames.length === 0) {
    if (!incremental) { destroyCharts(); wrap.innerHTML = ''; empty.style.display = 'block'; }
    return;
  }
  empty.style.display = 'none';

  // Удалить чарты, которых больше нет.
  Object.keys(nm.charts).forEach(name => {
    if (!metricNames.includes(name)) {
      nm.charts[name].destroy();
      delete nm.charts[name];
      nm.hiddenCharts.delete(name);
      const el = document.getElementById('chart-card-' + cssId(name));
      if (el) el.remove();
    }
  });

  // Границы окна: либо custom-диапазон, либо последние windowSeconds.
  let fromIso, toIso;
  if (nm.windowSeconds > 0) {
    fromIso = new Date(Date.now() - nm.windowSeconds * 1000).toISOString();
    toIso   = new Date().toISOString();
  } else {
    fromIso = nm.customFrom;
    toIso   = nm.customTo;
  }

  await Promise.all(metricNames.map(name => upsertChart(nodeId, name, fromIso, toIso, latest[name])));
  updateHiddenChartsButton();
}

async function upsertChart(nodeId, name, from, to, latestPoint) {
  let points;
  try {
    points = await api(
      `/api/v1/metrics/timeseries?nodeId=${encodeURIComponent(nodeId)}` +
      `&name=${encodeURIComponent(name)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}` +
      `&bucketSeconds=${nm.bucketSeconds}`
    );
  } catch (e) {
    console.error('timeseries error for', name, e); return;
  }

  // Если custom-диапазон — лейблы по абсолютному времени; иначе по
  // длительности окна выбираем формат HH:MM:SS / HH:MM / DD.MM HH:MM.
  const labels   = points.map(p => formatTimeLabel(new Date(p.timestamp), nm.windowSeconds || (new Date(to) - new Date(from)) / 1000));
  const tooltips = points.map(p => new Date(p.timestamp).toLocaleString());
  const avg = points.map(p => p.avg);
  const min = points.map(p => p.min);
  const max = points.map(p => p.max);
  const lastVal = latestPoint != null ? Number(latestPoint.value).toFixed(3) : '—';
  const unit    = latestPoint && latestPoint.unit ? latestPoint.unit : '';

  let card = document.getElementById('chart-card-' + cssId(name));
  if (!card) {
    card = document.createElement('div');
    card.className = 'chart-card';
    card.id = 'chart-card-' + cssId(name);
    card.innerHTML = `
      <div class="chart-head">
        <div>
          <h3>${escapeHtml(metricLabel(name))}</h3>
          <div class="sub">${escapeHtml(name)} · последнее: <b id="last-${cssId(name)}">${lastVal}</b> ${escapeHtml(unit)} · точек: <span id="cnt-${cssId(name)}">${points.length}</span></div>
        </div>
        <div style="display:flex; gap:6px; flex-shrink:0;">
          <button class="analyze-btn" onclick="openAnalysisModal('${name.replace(/'/g, "\\'")}')">Анализ корреляции</button>
          <button class="secondary-btn" onclick="hideChart('${name.replace(/'/g, "\\'")}')">Скрыть</button>
        </div>
      </div>
      <div class="canvas-wrap"><canvas id="canvas-${cssId(name)}"></canvas></div>
    `;
    document.getElementById('nmCharts').appendChild(card);
  } else {
    document.getElementById('last-' + cssId(name)).textContent = lastVal;
    document.getElementById('cnt-' + cssId(name)).textContent  = points.length;
  }
  // сохраняем «скрытость» между авто-обновлениями (loadCharts вызывается каждые 5с)
  card.style.display = nm.hiddenCharts.has(name) ? 'none' : '';

  const ctx = document.getElementById('canvas-' + cssId(name));
  const pr = points.length <= 20 ? 3 : (points.length <= 60 ? 2 : 0);

  if (nm.charts[name]) {
    const c = nm.charts[name];
    c.data.labels = labels;
    c._tooltipLabels = tooltips;
    c.data.datasets[0].data = avg; c.data.datasets[0].pointRadius = pr;
    c.data.datasets[1].data = min; c.data.datasets[1].pointRadius = pr;
    c.data.datasets[2].data = max; c.data.datasets[2].pointRadius = pr;
    c.update('none');
  } else {
    const chart = new Chart(ctx, {
      type: 'line',
      data: {
        labels,
        datasets: [
          { label: 'avg', data: avg, borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,.15)',
            borderWidth: 2, pointRadius: pr, pointHoverRadius: 5, pointBackgroundColor: '#3b82f6',
            tension: .25, fill: true, spanGaps: true },
          { label: 'min', data: min, borderColor: '#22c55e',
            borderWidth: 1, pointRadius: pr, pointHoverRadius: 4, pointBackgroundColor: '#22c55e',
            tension: .25, spanGaps: true },
          { label: 'max', data: max, borderColor: '#ef4444',
            borderWidth: 1, pointRadius: pr, pointHoverRadius: 4, pointBackgroundColor: '#ef4444',
            tension: .25, spanGaps: true },
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { labels: { color: '#cbd5e1', font: { size: 11 } } },
          tooltip: {
            callbacks: {
              title: items => {
                if (!items.length) return '';
                const ch = items[0].chart;
                return (ch._tooltipLabels && ch._tooltipLabels[items[0].dataIndex]) || items[0].label;
              }
            }
          }
        },
        scales: {
          x: { ticks: { color: '#94a3b8', maxRotation: 0, autoSkip: true, maxTicksLimit: 6 }, grid: { color: '#1e293b' } },
          y: { ticks: { color: '#94a3b8' }, grid: { color: '#1e293b' } },
        }
      }
    });
    chart._tooltipLabels = tooltips;
    nm.charts[name] = chart;
  }
}

// Короткая метка для оси X в зависимости от длины окна.
function formatTimeLabel(d, windowSeconds) {
  const pad = n => String(n).padStart(2, '0');
  if (windowSeconds <= 3600)  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  if (windowSeconds <= 86400) return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function deleteCurrentNode() {
  if (!nm.node) return;
  if (!confirm(`Удалить узел "${nm.node.name}"? Все его метрики будут удалены, активные алерты — разрешены.`)) return;
  try {
    await api('/api/v1/nodes/' + nm.node.id, { method: 'DELETE' });
    closeNodeModal();
    refresh();
  } catch (e) {
    alert('Не удалось удалить: ' + e.message);
  }
}
