// Модал «Контакты для алертов»: email + Telegram chat_id текущего пользователя.

async function openContactsModal() {
  document.getElementById('ctcErr').textContent = '';
  document.getElementById('ctcEmail').value = '';
  document.getElementById('ctcTelegramChatId').value = '';
  const statusEl = document.getElementById('ctcStatus');
  if (statusEl) statusEl.textContent = 'проверка…';
  try {
    const me = await api('/api/v1/auth/me');
    document.getElementById('ctcEmail').value          = me.email || '';
    document.getElementById('ctcTelegramChatId').value = me.telegramChatId || '';
  } catch (e) {
    document.getElementById('ctcErr').textContent = 'Не удалось загрузить контакты: ' + e.message;
  }
  if (statusEl) {
    try {
      const s = await api('/api/v1/notifications/status');
      const tg = s.telegramConfigured ? '✓ настроен' : '✗ не настроен админом';
      const em = s.emailConfigured ? ('✓ ' + (s.emailFrom || 'настроен')) : '✗ не настроен админом';
      statusEl.innerHTML = 'Telegram: <b>' + tg + '</b>, Email: <b>' + em + '</b>';
      statusEl.style.color = (s.telegramConfigured || s.emailConfigured) ? '#94a3b8' : '#f59e0b';
    } catch {
      statusEl.textContent = '';
    }
  }
  document.getElementById('contactsModal').classList.add('open');
}

function closeContactsModal() {
  document.getElementById('contactsModal').classList.remove('open');
}

async function saveContacts() {
  const errEl = document.getElementById('ctcErr');
  errEl.textContent = '';
  const email          = document.getElementById('ctcEmail').value.trim();
  const telegramChatId = document.getElementById('ctcTelegramChatId').value.trim();

  if (telegramChatId && !/^-?\d+$/.test(telegramChatId)) {
    errEl.textContent = 'chat_id должен быть числом (можно отрицательным для каналов).';
    return;
  }
  try {
    await api('/api/v1/auth/me/contacts', {
      method: 'PUT',
      body: JSON.stringify({
        email: email || null,
        telegramChatId: telegramChatId || null,
      }),
    });
    closeContactsModal();
  } catch (e) {
    errEl.textContent = 'Ошибка сохранения: ' + e.message;
  }
}
