// Bootstrap: глобальные обработчики и стартовый экран.

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (document.getElementById('analysisModal').classList.contains('open')) {
    closeAnalysisModal();
  } else if (document.getElementById('nodeModal').classList.contains('open')) {
    closeNodeModal();
  } else if (document.getElementById('ruleModal').classList.contains('open')) {
    closeRuleModal();
  } else if (document.getElementById('wizModal').classList.contains('open')) {
    closeAddNodeWizard();
  }
});

['authUsername', 'authPassword'].forEach(id => {
  document.getElementById(id).addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doLogin();
  });
});

if (getToken()) showApp(); else showAuth();
