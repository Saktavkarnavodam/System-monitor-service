// Окно корреляционного анализа: запрос /metrics/analyze + рендер.

async function openAnalysisModal(metricName) {
  if (!nm.node) return;
  const modal = document.getElementById('analysisModal');
  modal.classList.add('open');
  document.getElementById('anLoading').style.display = 'block';
  document.getElementById('anContent').style.display = 'none';
  document.getElementById('anMeta').textContent =
    `узел ${nm.node.name} · метрика ${metricName} · окно ${nm.windowSeconds || '—'} сек`;

  // Для custom-режима используем фактическую длительность интервала.
  const windowSec = nm.windowSeconds > 0
    ? nm.windowSeconds
    : Math.floor((new Date(nm.customTo) - new Date(nm.customFrom)) / 1000);
  const bucketSec = Math.max(10, Math.floor(windowSec / 60));

  try {
    const data = await api(
      `/api/v1/metrics/analyze?nodeId=${encodeURIComponent(nm.node.id)}` +
      `&name=${encodeURIComponent(metricName)}` +
      `&windowSeconds=${windowSec}` +
      `&bucketSeconds=${bucketSec}` +
      `&topN=8`
    );
    renderAnalysis(data);
  } catch (e) {
    document.getElementById('anLoading').textContent = 'Ошибка: ' + e.message;
  }
}

function closeAnalysisModal() {
  document.getElementById('analysisModal').classList.remove('open');
}

function renderAnalysis(data) {
  document.getElementById('anLoading').style.display = 'none';
  document.getElementById('anContent').style.display = 'block';

  const t = data.target || {};
  const card = document.getElementById('anTargetCard');
  card.classList.toggle('anomaly', !!t.anomaly);

  document.getElementById('anTargetName').textContent = metricLabel(t.name) || '—';

  const badges = [];
  badges.push(t.anomaly
    ? '<span class="badge anom">АНОМАЛИЯ</span>'
    : '<span class="badge norm">НОРМА</span>');
  if (t.trend === 'RISING')  badges.push('<span class="badge rising">РОСТ</span>');
  if (t.trend === 'FALLING') badges.push('<span class="badge falling">ПАДЕНИЕ</span>');
  if (t.trend === 'STABLE')  badges.push('<span class="badge stable">СТАБИЛЬНО</span>');
  document.getElementById('anTargetBadges').innerHTML = badges.join(' ');

  document.getElementById('anCurrent').textContent = fmtNum(t.currentValue);
  document.getElementById('anMean').textContent    = fmtNum(t.mean);
  document.getElementById('anStddev').textContent  = fmtNum(t.stddev);
  document.getElementById('anZ').textContent       = fmtNum(t.zScore);
  const sign = t.changePercent > 0 ? '+' : '';
  document.getElementById('anChange').textContent  = sign + fmtNum(t.changePercent) + ' %';
  document.getElementById('anSamples').textContent = t.sampleCount;

  document.getElementById('anSummary').textContent = data.summary || '—';

  const corrs = data.correlations || [];
  const list = document.getElementById('anCorrelations');
  const empty = document.getElementById('anEmptyCorrs');
  list.innerHTML = '';

  if (corrs.length === 0) { empty.style.display = 'block'; return; }
  empty.style.display = 'none';

  corrs.forEach(c => {
    const widthPct = Math.round(Math.abs(c.correlation) * 100);
    const dirClass = c.correlation >= 0 ? 'pos' : 'neg';
    const dirText  = c.correlation >= 0 ? 'синхронный рост/падение' : 'обратное движение';
    const changeSign = c.changePercent > 0 ? '+' : '';
    const row = document.createElement('div');
    row.className = 'corr-row';
    row.innerHTML = `
      <div class="top">
        <div class="name">${escapeHtml(metricLabel(c.name))}</div>
        <div style="font-size:10px;color:#64748b;">${escapeHtml(c.name)}</div>
        <div class="badges">
          <span class="badge strength-${c.strength}">${c.strength}</span>
          <span style="font-family: monospace; font-size: 13px; color:#cbd5e1;">r = ${fmtNum(c.correlation, 3)}</span>
        </div>
      </div>
      <div class="bar-wrap"><div class="bar ${dirClass}" style="width: ${widthPct}%"></div></div>
      <div class="details">
        ${dirText} · текущее: ${fmtNum(c.currentValue)} · среднее: ${fmtNum(c.mean)} · изменение: ${changeSign}${fmtNum(c.changePercent)}%
      </div>
    `;
    list.appendChild(row);
  });
}
