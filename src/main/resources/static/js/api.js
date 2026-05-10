// REST-клиент + аутентификация.

const TOKEN_KEY = 'monitoring.jwt';
const USER_KEY  = 'monitoring.user';

const getToken = () => localStorage.getItem(TOKEN_KEY);

function setSession(resp) {
  localStorage.setItem(TOKEN_KEY, resp.token);
  localStorage.setItem(USER_KEY, JSON.stringify({ id: resp.userId, username: resp.username, role: resp.role }));
}

function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

// Универсальный fetch: добавляет Bearer-токен, на 401/403 → выкидывает на логин.
async function api(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401 || res.status === 403) {
    clearSession();
    showAuth();
    throw new Error('Не авторизован');
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || ('HTTP ' + res.status));
  }
  if (res.status === 204) return null;
  return res.json();
}

async function doLogin() {
  const username = document.getElementById('authUsername').value.trim();
  const password = document.getElementById('authPassword').value;
  if (!username || !password) {
    document.getElementById('authErr').textContent = 'Введите имя пользователя и пароль';
    return;
  }
  try {
    const data = await postAuth('/api/v1/auth/login', { username, password });
    setSession(data);
    showApp();
  } catch (e) {
    document.getElementById('authErr').textContent = e.message;
  }
}

async function doRegister() {
  const username = document.getElementById('authUsername').value.trim();
  const password = document.getElementById('authPassword').value;
  if (!username || password.length < 6) {
    document.getElementById('authErr').textContent = 'Имя >= 3 символов, пароль >= 6 символов';
    return;
  }
  try {
    const data = await postAuth('/api/v1/auth/register', { username, password });
    setSession(data);
    showApp();
  } catch (e) {
    document.getElementById('authErr').textContent = e.message;
  }
}

// Хелпер для login/register: те же правила, что в api(), но без сессии.
async function postAuth(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || ('Ошибка ' + res.status));
  }
  return res.json();
}

function logout() {
  clearSession();
  showAuth();
}

function showAuth() {
  document.getElementById('authOverlay').style.display = 'flex';
  document.getElementById('appUI').style.display = 'none';
  document.getElementById('authErr').textContent = '';
}

function showApp() {
  document.getElementById('authOverlay').style.display = 'none';
  document.getElementById('appUI').style.display = 'block';
  const user = JSON.parse(localStorage.getItem(USER_KEY) || '{}');
  document.getElementById('currentUser').textContent = user.username || '?';
  document.getElementById('currentRole').textContent = user.role === 'ADMIN' ? '(admin)' : '';
  refresh();
  if (window._refreshInterval) clearInterval(window._refreshInterval);
  window._refreshInterval = setInterval(refresh, 5000);
}
