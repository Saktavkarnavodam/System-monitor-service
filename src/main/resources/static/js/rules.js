// CRUD правил алертов: таблица в дашборде + модал создания/редактирования.

let _rulesCache = { rules: [], nodes: [] };

const COND_LABEL = { GT: '>', GTE: '⩾', LT: '<', LTE: '⩽', EQ: '=', NEQ: '≠' };

async function refreshRules(nodes) {
  const tbody = document.getElementById('rulesTbody');
  if (!tbody) return;
  let rules;
  try {
    rules = await api('/api/v1/alerts/rules');
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="8" class="muted">Не удалось загрузить правила: ${escapeHtml(e.message)}</td></tr>`;
    return;
  }
  _rulesCache.rules = rules || [];
  _rulesCache.nodes = nodes || [];

  if (rules.length === 0) {
    tbody.innerHTML = '<tr><td colspan="8" class="muted">Правил пока нет. Нажми «+ Создать правило».</td></tr>';
    return;
  }
  const nodeNameById = {};
  (nodes || []).forEach(n => { nodeNameById[n.id] = n.name; });

  tbody.innerHTML = '';
  rules.forEach(r => {
    const tr = document.createElement('tr');
    tr.className = 'rule-row' + (r.enabled ? '' : ' rule-disabled');
    const nodeCell = r.nodeId
      ? escapeHtml(nodeNameById[r.nodeId] || r.nodeId.slice(0, 8) + '…')
      : '<span class="muted">все</span>';
    const cond = `${escapeHtml(r.condition)} ${COND_LABEL[r.condition] || ''} ${fmtNum(r.threshold, 2)}`;
    tr.innerHTML = `
      <td>${escapeHtml(r.name)}</td>
      <td><span class="pill">${escapeHtml(r.metricName)}</span></td>
      <td>${nodeCell}</td>
      <td><span class="pill">${cond}</span></td>
      <td>${r.durationSeconds} сек</td>
      <td class="sev-${r.severity}">${r.severity}</td>
      <td>${r.enabled ? '✓' : '—'}</td>
      <td><div class="rule-actions">
        <button class="secondary-btn" onclick='openRuleModal(${JSON.stringify(r.id)})'>Изменить</button>
        <button class="danger-btn"    onclick='deleteRule(${JSON.stringify(r.id)}, ${JSON.stringify(r.name)})'>Удалить</button>
      </div></td>
    `;
    tbody.appendChild(tr);
  });
}

function openRuleModal(id) {
  document.getElementById('ruleErr').textContent = '';
  document.getElementById('ruleHint').textContent = '';

  // Подставить актуальный список узлов в селект.
  const sel = document.getElementById('ruleNodeId');
  sel.innerHTML = '<option value="">— все узлы —</option>';
  (_rulesCache.nodes || []).forEach(n => {
    const opt = document.createElement('option');
    opt.value = n.id;
    opt.textContent = n.name;
    sel.appendChild(opt);
  });

  loadKnownMetrics();

  if (id) {
    const r = (_rulesCache.rules || []).find(x => x.id === id);
    if (!r) return;
    document.getElementById('ruleModalTitle').textContent = 'Редактировать правило';
    document.getElementById('ruleId').value          = r.id;
    document.getElementById('ruleName').value        = r.name || '';
    document.getElementById('ruleMetricName').value  = r.metricName || '';
    document.getElementById('ruleNodeId').value      = r.nodeId || '';
    document.getElementById('ruleCondition').value   = r.condition || 'GT';
    document.getElementById('ruleThreshold').value   = r.threshold;
    document.getElementById('ruleDuration').value    = r.durationSeconds || 30;
    document.getElementById('ruleSeverity').value    = r.severity || 'WARNING';
    document.getElementById('ruleDescription').value = r.description || '';
    document.getElementById('ruleEnabled').checked   = !!r.enabled;
    document.getElementById('ruleNotifyTelegram').checked = !!r.notifyTelegram;
    document.getElementById('ruleNotifyEmail').checked    = !!r.notifyEmail;
    document.getElementById('ruleDeleteBtn').style.display = 'inline-block';
  } else {
    document.getElementById('ruleModalTitle').textContent = 'Новое правило алерта';
    document.getElementById('ruleId').value          = '';
    document.getElementById('ruleName').value        = '';
    document.getElementById('ruleMetricName').value  = '';
    document.getElementById('ruleNodeId').value      = '';
    document.getElementById('ruleCondition').value   = 'GT';
    document.getElementById('ruleThreshold').value   = '';
    document.getElementById('ruleDuration').value    = '30';
    document.getElementById('ruleSeverity').value    = 'WARNING';
    document.getElementById('ruleDescription').value = '';
    document.getElementById('ruleEnabled').checked   = true;
    document.getElementById('ruleNotifyTelegram').checked = false;
    document.getElementById('ruleNotifyEmail').checked    = false;
    document.getElementById('ruleDeleteBtn').style.display = 'none';
  }
  document.getElementById('ruleModal').classList.add('open');
}

function closeRuleModal() {
  document.getElementById('ruleModal').classList.remove('open');
}

async function loadKnownMetrics() {
  let names;
  try { names = await api('/api/v1/metrics/names'); } catch { return; }
  if (!Array.isArray(names) || names.length === 0) return;
  const hint = document.getElementById('ruleHint');
  hint.innerHTML = 'Известные метрики: ';
  names.slice(0, 12).forEach(n => {
    const span = document.createElement('span');
    span.className = 'pill';
    span.style.cursor = 'pointer';
    span.textContent = n;
    span.onclick = () => { document.getElementById('ruleMetricName').value = n; };
    hint.appendChild(span);
    hint.appendChild(document.createTextNode(' '));
  });
}

async function saveRule() {
  const errEl = document.getElementById('ruleErr');
  errEl.textContent = '';

  const id          = document.getElementById('ruleId').value.trim();
  const name        = document.getElementById('ruleName').value.trim();
  const metricName  = document.getElementById('ruleMetricName').value.trim();
  const nodeId      = document.getElementById('ruleNodeId').value.trim();
  const condition   = document.getElementById('ruleCondition').value;
  const threshold   = parseFloat(document.getElementById('ruleThreshold').value);
  const duration    = parseInt(document.getElementById('ruleDuration').value, 10);
  const severity    = document.getElementById('ruleSeverity').value;
  const description = document.getElementById('ruleDescription').value.trim();
  const enabled     = document.getElementById('ruleEnabled').checked;
  const notifyTelegram = document.getElementById('ruleNotifyTelegram').checked;
  const notifyEmail    = document.getElementById('ruleNotifyEmail').checked;

  if (!name)                     { errEl.textContent = 'Укажите имя правила'; return; }
  if (!metricName)               { errEl.textContent = 'Укажите имя метрики'; return; }
  if (Number.isNaN(threshold))   { errEl.textContent = 'Порог должен быть числом'; return; }
  if (!duration || duration < 1) { errEl.textContent = 'Длительность должна быть >= 1 секунды'; return; }

  const body = {
    name, metricName,
    nodeId: nodeId || null,
    condition, threshold,
    durationSeconds: duration,
    severity,
    description: description || null,
    enabled,
    notifyTelegram,
    notifyEmail,
  };
  if (id) body.id = id;

  // Быстрая проверка на дубль (та же логика и на сервере): одинаковые метрика +
  // условие + порог + узел = оба правила сработают вместе и уведомления придут дважды.
  const dup = (_rulesCache.rules || []).find(r =>
    r.id !== id &&
    (r.metricName || '').trim() === metricName &&
    r.condition === condition &&
    Number(r.threshold) === threshold &&
    (r.nodeId || null) === (nodeId || null));
  if (dup) {
    errEl.textContent = `Дубль: правило «${dup.name}» уже отслеживает ${metricName} ${COND_LABEL[condition] || condition} ${threshold}` +
      `${nodeId ? ' на этом узле' : ' (все узлы)'}. Уведомления придут дважды — измените метрику, условие или порог.`;
    return;
  }

  try {
    await api('/api/v1/alerts/rules', { method: 'POST', body: JSON.stringify(body) });
    closeRuleModal();
    refresh();
  } catch (e) {
    errEl.textContent = 'Ошибка: ' + e.message;
  }
}

async function deleteRule(id, name) {
  if (!confirm(`Удалить правило «${name}»? Активные алерты по нему будут разрешены.`)) return;
  try {
    await api('/api/v1/alerts/rules/' + id, { method: 'DELETE' });
    refresh();
  } catch (e) {
    alert('Не удалось удалить: ' + e.message);
  }
}

async function deleteRuleFromModal() {
  const id   = document.getElementById('ruleId').value.trim();
  const name = document.getElementById('ruleName').value.trim();
  if (!id) return;
  if (!confirm(`Удалить правило «${name}»? Активные алерты по нему будут разрешены.`)) return;
  try {
    await api('/api/v1/alerts/rules/' + id, { method: 'DELETE' });
    closeRuleModal();
    refresh();
  } catch (e) {
    document.getElementById('ruleErr').textContent = 'Ошибка: ' + e.message;
  }
}
