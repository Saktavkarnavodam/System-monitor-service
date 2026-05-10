// Сводный дашборд: счётчики, таблицы узлов, алертов, правил.

async function refresh() {
  let d;
  try {
    d = await api('/api/v1/dashboard/overview');
  } catch (e) {
    console.error(e);
    return;
  }

  document.getElementById('totalNodes').textContent    = d.totalNodes;
  document.getElementById('activeAlerts').textContent  = d.activeAlerts;
  document.getElementById('storedMetrics').textContent = d.totalMetricsStored;
  document.getElementById('genAt').textContent         = fmt(d.generatedAt);
  document.getElementById('updated').textContent       = 'Обновлено: ' + fmt(d.generatedAt);

  fillCounts('nodeStatusGrid', d.nodeStatusCounts, 'status');
  fillCounts('alertSevGrid',   d.alertSeverityCounts, 'sev');
  fillNodes(d.nodes || []);
  fillAlerts(d.recentAlerts || []);

  // Правила алертов — отдельный запрос (нет в overview).
  refreshRules(d.nodes || []);
}

function fillCounts(elemId, map, cls) {
  const el = document.getElementById(elemId);
  el.innerHTML = '';
  Object.entries(map || {}).forEach(([k, v]) => {
    el.insertAdjacentHTML('beforeend',
      `<div><div class="muted">${k}</div><div class="${cls}-${k}" style="font-size:20px">${v}</div></div>`);
  });
}

function fillNodes(nodes) {
  const tb = document.getElementById('nodesTbody');
  tb.innerHTML = '';
  nodes.forEach(n => {
    const tr = document.createElement('tr');
    tr.className = 'clickable';
    tr.onclick = () => openNodeModal(n);
    const addr = (n.host || '') + (n.port ? ':' + n.port : '');
    tr.innerHTML =
      `<td>${escapeHtml(n.name)}</td>
       <td>${escapeHtml(n.type || '')}</td>
       <td>${escapeHtml(addr)}</td>
       <td class="status-${n.status}">${n.status}</td>
       <td>${fmt(n.lastHeartbeat)}</td>
       <td class="muted">${n.id}</td>`;
    tb.appendChild(tr);
  });
}

function fillAlerts(alerts) {
  const ab = document.getElementById('alertsTbody');
  ab.innerHTML = '';
  alerts.forEach(a => {
    ab.insertAdjacentHTML('beforeend',
      `<tr><td>${fmt(a.firedAt)}</td>
           <td>${escapeHtml(a.ruleName || '')}</td>
           <td>${escapeHtml(a.metricName)}</td>
           <td>${a.value != null ? a.value.toFixed(3) : ''}</td>
           <td class="sev-${a.severity}">${a.severity}</td>
           <td>${a.status}</td></tr>`);
  });
}
