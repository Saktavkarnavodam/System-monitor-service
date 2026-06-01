// Визард «+ Добавить узел» и «Получить команду установки заново»:
// при открытии сразу выпускает agent-токен под капотом и показывает готовую
// install-команду. Токен пользователю не показывается — он встроен в команду.
// Install-команда зовёт install-linux.sh / install-windows.ps1, которые
// разворачивают агента как systemd-сервис / Scheduled Task с автоперезапуском
// и автостартом при загрузке узла.

const wiz = {
  selectedOs: 'linux',
  issuedToken: null,
  nodeName: '',
  reconnectMode: false,
};

function openAddNodeWizard(opts) {
  opts = opts || {};
  wiz.reconnectMode = !!opts.reconnectMode;
  wiz.issuedToken = null;
  document.getElementById('wizTokenName').value = '';
  document.getElementById('wizNodeName').value  = opts.prefilledNodeName || '';
  document.getElementById('wizStep1').style.display = 'block';
  document.getElementById('wizStep2').style.display = 'none';
  document.getElementById('wizStep').textContent = '1';

  // В reconnect-режиме ОС известна (узел уже зарегистрирован, у него есть
  // tags.os) — выбираем её автоматически и прячем табы выбора.
  const osTabsEl = document.querySelector('.os-tabs');
  const osLabelEl = document.getElementById('wizOsLabel');
  if (wiz.reconnectMode && opts.node) {
    const detectedOs = detectOsFromNode(opts.node);
    selectOs(detectedOs);
    if (osTabsEl) osTabsEl.style.display = 'none';
    if (osLabelEl) osLabelEl.style.display = 'none';
  } else {
    if (osTabsEl) osTabsEl.style.display = '';
    if (osLabelEl) osLabelEl.style.display = '';
    selectOs('linux');
  }

  // Заголовок и подсказка зависят от режима.
  const titleEl = document.querySelector('#wizModal h2');
  const hintEl  = document.getElementById('wizReconnectHint');
  if (wiz.reconnectMode) {
    const osLabel = wiz.selectedOs === 'windows' ? 'Windows' : 'Linux';
    if (titleEl) titleEl.textContent = `Переустановка агента «${opts.prefilledNodeName || ''}» (${osLabel})`;
    if (hintEl)  hintEl.style.display = 'block';
  } else {
    if (titleEl) titleEl.textContent = 'Добавить узел';
    if (hintEl)  hintEl.style.display = 'none';
  }

  document.getElementById('wizModal').classList.add('open');
  refreshTokenList();
}

/** Угадывает ОС по tags.os, проставленным агентом при регистрации. */
function detectOsFromNode(node) {
  const os = (node && node.tags && (node.tags.os || '')).toLowerCase();
  if (os.startsWith('win')) return 'windows';
  return 'linux';
}

/**
 * Открывает визард в режиме «дай мне команду установки заново» для конкретного
 * узла. Используется на карточке узла: ситуация «потерял команду / переставляю
 * с нуля / агент удалён».
 */
function openReconnectWizard(node) {
  openAddNodeWizard({ reconnectMode: true, prefilledNodeName: node.name, node: node });
}

function closeAddNodeWizard() {
  document.getElementById('wizModal').classList.remove('open');
  wiz.issuedToken = null;
}

function selectOs(os) {
  wiz.selectedOs = os;
  document.querySelectorAll('.os-tab').forEach(el => {
    el.classList.toggle('active', el.dataset.os === os);
  });
  // На втором шаге — обновить команду без перевыпуска токена.
  if (document.getElementById('wizStep2').style.display !== 'none' && wiz.issuedToken) {
    document.getElementById('wizInstallCmdText').textContent = buildInstallCmd();
  }
}

/**
 * Шаг «Получить команду установки»: выпускает свежий agent-токен под капотом
 * и показывает готовую команду. Пользователь не видит сам токен — только команду.
 */
async function wizGenerateToken() {
  const name = document.getElementById('wizTokenName').value.trim()
            || (wiz.reconnectMode ? `reinstall-${Date.now()}` : 'agent');
  wiz.nodeName = document.getElementById('wizNodeName').value.trim();
  try {
    const res = await api('/api/v1/agent-tokens', {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
    wiz.issuedToken = res.token;
    document.getElementById('wizInstallCmdText').textContent = buildInstallCmd();
    document.getElementById('wizStep1').style.display = 'none';
    document.getElementById('wizStep2').style.display = 'block';
    document.getElementById('wizStep').textContent = '2';
    refreshTokenList();
  } catch (e) {
    alert('Не удалось подготовить команду: ' + e.message);
  }
}

function wizBack() {
  document.getElementById('wizStep1').style.display = 'block';
  document.getElementById('wizStep2').style.display = 'none';
  document.getElementById('wizStep').textContent = '1';
  wiz.issuedToken = null;
}

// Готовая install-команда для выбранной ОС. Команда зовёт install-linux.sh /
// install-windows.ps1, которые ставят агента как сервис с автоперезапуском.
function buildInstallCmd() {
  const url   = window.location.origin;
  const token = wiz.issuedToken;
  const node  = wiz.nodeName;
  const isHttps = url.startsWith('https://');

  if (wiz.selectedOs === 'windows') {
    const nodeArg = node ? ` -NodeName '${node.replace(/'/g, "''")}'` : '';
    // [scriptblock]::Create вместо iex — короче и без промежуточного файла.
    // .TrimStart([char]0xFEFF) срезает UTF-8 BOM, который сервер добавляет в .ps1
    // (нужен для -OutFile на русской Windows). Без trim BOM ломает разбор param().
    const parts = [];
    if (isHttps) parts.push(`[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;`);
    parts.push(`& ([scriptblock]::Create((iwr '${url}/install/install-windows.ps1' -UseBasicParsing).Content.TrimStart([char]0xFEFF))) -Server '${url}' -Token '${token}'${nodeArg}`);
    const ps = parts.join(' ');
    return `:: Windows: запускать в КОМАНДНОЙ СТРОКЕ (cmd.exe) от имени администратора.\n:: НЕ в PowerShell — там кавычки разбираются иначе и агент не поднимется.\npowershell -NoP -ep Bypass -Command "${ps}"`;
  }

  const nodeArg = node ? ` --node-name "${node}"` : '';
  // linux: один скрипт ставит систем-сервис, нужен sudo + python3
  return `# Linux (sudo, нужен python3 + curl):\ncurl -fsSL ${url}/install/install-linux.sh | sudo bash -s -- --server ${url} --token ${token}${nodeArg}`;
}

function copyInstallCmd(btn) {
  const text = document.getElementById('wizInstallCmdText').textContent;
  navigator.clipboard.writeText(text).then(() => {
    btn.textContent = 'Скопировано';
    btn.classList.add('copied');
    setTimeout(() => { btn.textContent = 'Копировать'; btn.classList.remove('copied'); }, 1500);
  });
}

async function refreshTokenList() {
  const body = document.getElementById('wizTokenListBody');
  let tokens;
  try {
    tokens = await api('/api/v1/agent-tokens');
  } catch (e) {
    body.innerHTML = '<div class="muted" style="font-size: 12px;">Не удалось загрузить токены: ' + escapeHtml(e.message) + '</div>';
    return;
  }
  if (tokens.length === 0) {
    body.innerHTML = '<div class="muted" style="font-size: 12px;">Пока нет ни одного токена.</div>';
    return;
  }
  body.innerHTML = '';
  tokens.forEach(t => {
    const row = document.createElement('div');
    row.className = 'token-row' + (t.revoked ? ' revoked' : '');
    const lastUsed = t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'ни разу';
    const created  = t.createdAt  ? new Date(t.createdAt).toLocaleString()  : '?';
    row.innerHTML = `
      <div>
        <div class="name">${escapeHtml(t.name)} ${t.revoked ? '<span class="muted">(отозван)</span>' : ''}</div>
        <div class="meta">создан: ${escapeHtml(created)} · последний вызов: ${escapeHtml(lastUsed)} · <span class="suffix">…${escapeHtml(t.tokenSuffix)}</span></div>
      </div>
      ${t.revoked ? '' : `<button class="danger-btn" onclick="revokeToken('${t.id}')">Отозвать</button>`}
    `;
    body.appendChild(row);
  });
}

async function revokeToken(id) {
  if (!confirm('Отозвать этот токен? Все агенты, использующие его, перестанут работать.')) return;
  try {
    await api('/api/v1/agent-tokens/' + id, { method: 'DELETE' });
    refreshTokenList();
  } catch (e) {
    alert('Не удалось отозвать: ' + e.message);
  }
}
