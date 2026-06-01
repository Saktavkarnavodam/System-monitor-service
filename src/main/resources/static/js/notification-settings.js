// Админ-модал «Настройки уведомлений»: бот-токен Telegram + SMTP-кред.
// Видим только для роли ADMIN (см. api.js → showApp).

async function openNotifSettings() {
  setNsMsg('', '');
  document.getElementById('nsStatusVal').textContent = 'загрузка…';
  try {
    const dto = await api('/api/v1/notifications/settings');
    document.getElementById('nsTgToken').value   = dto.telegramBotToken || '';
    document.getElementById('nsSmtpHost').value  = dto.smtpHost || '';
    document.getElementById('nsSmtpPort').value  = dto.smtpPort || 587;
    document.getElementById('nsSmtpUser').value  = dto.smtpUsername || '';
    // пароль не возвращается в открытом виде — оставляем поле пустым;
    // плейсхолдер показывает, что пароль уже сохранён
    document.getElementById('nsSmtpPass').value  = '';
    document.getElementById('nsSmtpPass').placeholder = dto.smtpPassword ? '•••••••• (сохранён, оставьте пусто чтобы не менять)' : 'пароль приложения';
    document.getElementById('nsEmailFrom').value = dto.emailFrom || '';
    document.getElementById('nsStarttls').checked = !!dto.smtpStarttls;
    document.getElementById('nsStatusVal').textContent = dto.status || '—';
  } catch (e) {
    setNsMsg('Не удалось загрузить настройки: ' + e.message, '');
  }
  document.getElementById('notifSettingsModal').classList.add('open');
}

function closeNotifSettings() {
  document.getElementById('notifSettingsModal').classList.remove('open');
}

async function saveNotifSettings() {
  setNsMsg('', '');
  const body = {
    telegramBotToken: document.getElementById('nsTgToken').value.trim() || null,
    smtpHost:         document.getElementById('nsSmtpHost').value.trim() || null,
    smtpPort:         parseInt(document.getElementById('nsSmtpPort').value, 10) || null,
    smtpUsername:     document.getElementById('nsSmtpUser').value.trim() || null,
    smtpPassword:     document.getElementById('nsSmtpPass').value, // пустая строка = не менять
    smtpStarttls:     document.getElementById('nsStarttls').checked,
    emailFrom:        document.getElementById('nsEmailFrom').value.trim() || null,
  };
  try {
    const dto = await api('/api/v1/notifications/settings', {
      method: 'PUT', body: JSON.stringify(body),
    });
    document.getElementById('nsStatusVal').textContent = dto.status || '—';
    document.getElementById('nsSmtpPass').value = '';
    document.getElementById('nsSmtpPass').placeholder = dto.smtpPassword ? '•••••••• (сохранён, оставьте пусто чтобы не менять)' : 'пароль приложения';
    setNsMsg('', 'Сохранено.');
  } catch (e) {
    setNsMsg('Ошибка: ' + e.message, '');
  }
}

async function testNotif(channel) {
  setNsMsg('', 'Отправка…');
  try {
    const r = await api('/api/v1/notifications/test?channel=' + encodeURIComponent(channel), {
      method: 'POST',
    });
    setNsMsg('', r.message || ('Тест ' + channel + ' отправлен.'));
  } catch (e) {
    setNsMsg('Тест ' + channel + ' не удался: ' + e.message, '');
  }
}

function setNsMsg(err, ok) {
  document.getElementById('nsErr').textContent = err || '';
  document.getElementById('nsOk').textContent  = ok  || '';
}
