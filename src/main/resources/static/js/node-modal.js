// Модал узла: переключатели окна/бакета, сетка графиков Chart.js,
// произвольный временной интервал.

const nm = {
  node: null,
  windowSeconds: 3600,        // длина окна; 0 при custom-режиме
  bucketSeconds: 60,
  customFrom: null,            // ISO строки для custom-режима
  customTo:   null,
  charts: {},                  // metricName -> Chart.js instance
  refreshTimer: null,
};

function openNodeModal(node) {
  nm.node = node;
  document.getElementById('nmName').textContent = node.name;
  document.getElementById('nmMeta').innerHTML = [
    escapeHtml(node.type || 'service'),
    escapeHtml((node.host || '—') + (node.port ? ':' + node.port : '')),
    `<span class="status-${node.status}">${node.status}</span>`,
    `heartbeat: ${fmt(node.lastHeartbeat)}`,
    `id: <span class="muted">${node.id}</span>`,
  ].join(' · ');
  document.getElementById('nodeModal').classList.add('open');

  destroyCharts();
  document.getElementById('nmCharts').innerHTML = '';
  loadCharts();

  if (nm.refreshTimer) clearInterval(nm.refreshTimer);
  nm.refreshTimer = setInterval(() => {
    if (document.getElementById('nmAutoRefresh').checked && nm.windowSeconds > 0) {
      // авто-обновление имеет смысл только для скользящего окна
      loadCharts(true);
    }
  }, 5000);
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
        <button class="analyze-btn" onclick="openAnalysisModal('${name.replace(/'/g, "\\'")}')">Анализ корреляции</button>
      </div>
      <div class="canvas-wrap"><canvas id="canvas-${cssId(name)}"></canvas></div>
    `;
    document.getElementById('nmCharts').appendChild(card);
  } else {
    document.getElementById('last-' + cssId(name)).textContent = lastVal;
    document.getElementById('cnt-' + cssId(name)).textContent  = points.length;
  }

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
