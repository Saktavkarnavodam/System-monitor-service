// Мелкие хелперы, общие для всех модулей.

const fmt = (iso) => iso ? new Date(iso).toLocaleString() : '—';

function fmtNum(v, decimals = 2) {
  if (v == null || isNaN(v)) return '—';
  return Number(v).toFixed(decimals);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// Человекочитаемые названия системных метрик (что приходит от агентов).
// Если метрика не в словаре — показываем имя как есть.
const METRIC_LABELS = {
  'cpu.usage':         'CPU — загрузка (%)',
  'cpu.count_logical': 'CPU — количество ядер',
  'memory.used_mb':    'Память — занято (МБ)',
  'memory.total_mb':   'Память — всего (МБ)',
  'memory.percent':    'Память — загрузка (%)',
  'disk.used_gb':      'Диск — занято (ГБ)',
  'disk.total_gb':     'Диск — всего (ГБ)',
  'disk.percent':      'Диск — заполненность (%)',
  'net.bytes_sent_ps': 'Сеть — отправка (байт/с)',
  'net.bytes_recv_ps': 'Сеть — приём (байт/с)',
  'process.count':     'Процессы — количество',
};
const metricLabel = name => METRIC_LABELS[name] || name;

// Безопасный CSS-id для произвольной строки (имя метрики → имя DOM-элемента).
const cssId = s => s.replace(/[^a-zA-Z0-9_-]/g, '_');
