// Визард «+ Добавить узел»: выпуск agent-токена + подбор install-команды.

const wiz = {
  selectedOs: 'linux',
  issuedToken: null,
  nodeName: '',
};

function openAddNodeWizard() {
  document.getElementById('wizTokenName').value = '';
  document.getElementById('wizNodeName').value  = '';
  document.getElementById('wizStep1').style.display = 'block';
  document.getElementById('wizStep2').style.display = 'none';
  document.getElementById('wizStep').textContent = '1';
  selectOs('linux');
  document.getElementById('wizModal').classList.add('open');
  refreshTokenList();
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

async function wizGenerateToken() {
  const name = document.getElementById('wizTokenName').value.trim() || 'agent';
  wiz.nodeName = document.getElementById('wizNodeName').value.trim();
  try {
    const res = await api('/api/v1/agent-tokens', {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
    wiz.issuedToken = res.token;
    document.getElementById('wizTokenValue').textContent = res.token;
    document.getElementById('wizInstallCmdText').textContent = buildInstallCmd();
    document.getElementById('wizStep1').style.display = 'none';
    document.getElementById('wizStep2').style.display = 'block';
    document.getElementById('wizStep').textContent = '2';
    refreshTokenList();
  } catch (e) {
    alert('Не удалось выпустить токен: ' + e.message);
  }
}

function wizBack() {
  document.getElementById('wizStep1').style.display = 'block';
  document.getElementById('wizStep2').style.display = 'none';
  document.getElementById('wizStep').textContent = '1';
  wiz.issuedToken = null;
}

// Готовая install-команда для выбранной ОС. Для Windows — однострочный
// powershell -Command, который работает и из cmd.exe, и из PowerShell.
function buildInstallCmd() {
  const url   = window.location.origin;
  const token = wiz.issuedToken;
  const node  = wiz.nodeName;

  if (wiz.selectedOs === 'windows') {
    const nodeArg = node ? ` -NodeName '${node.replace(/'/g, "''")}'` : '';
    const ps = [
      `$ErrorActionPreference='Stop';`,
      `[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;`,
      `$p=Join-Path $env:TEMP 'agent.ps1';`,
      `Invoke-WebRequest '${url}/install/agent.ps1' -UseBasicParsing -OutFile $p;`,
      `& $p -Server '${url}' -Token '${token}'${nodeArg}`,
    ].join(' ');
    return `# cmd.exe или PowerShell — одной строкой:\npowershell -NoProfile -ExecutionPolicy Bypass -Command "${ps}"`;
  }

  const nodeArg = node ? ` --node-name "${node}"` : '';
  if (wiz.selectedOs === 'bash') {
    return `# Linux/macOS, без Python — нужен только curl и bc:\ncurl -fsSL ${url}/install/agent.sh | bash -s -- --server ${url} --token ${token}${nodeArg}`;
  }
  // linux (Python — самое надёжное)
  return `# Linux/macOS, требуется Python 3 + psutil:\npip install --user psutil && curl -fsSL ${url}/install/agent.py | python3 - --server ${url} --token ${token}${nodeArg}`;
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
