/*
 * Rapfi panel: analysis-database settings + engine (NNUE) status.
 *
 * Injected at runtime by NativeRapfiPlugin. It is deliberately self-contained and attaches to
 * document.body (a sibling of the Vue root), so it never touches or conflicts with the packaged
 * Web UI bundle.
 *
 * Talks to the native layer through the Capacitor bridge:
 *   listDatabases / createDatabase / switchDatabase / deleteDatabase
 *   setDatabaseMode / getDatabaseStatus / getEngineStatus
 */
(function () {
  'use strict';

  function api(method, options) {
    var cap = window.Capacitor;
    if (cap && cap.Plugins && cap.Plugins.NativeRapfi
        && typeof cap.Plugins.NativeRapfi[method] === 'function') {
      return Promise.resolve(cap.Plugins.NativeRapfi[method](options || {}));
    }
    if (cap && typeof cap.nativePromise === 'function') {
      return cap.nativePromise('NativeRapfi', method, options || {});
    }
    return Promise.reject(new Error('Capacitor bridge unavailable'));
  }

  // Re-injecting after a WebView reload should just refresh the existing panel.
  if (window.__rapfiPanel) {
    try { window.__rapfiPanel.refresh(); } catch (e) { /* ignore */ }
    return;
  }

  var MODES = [
    { key: 'on', label: '使用数据库', hint: '读 + 写' },
    { key: 'readonly', label: '只读数据库', hint: '只读缓存，不写入' },
    { key: 'off', label: '不使用数据库', hint: '完全关闭' }
  ];

  var state = { mode: 'on', current: 'default', databases: ['default'], recordCount: -1 };

  // ------------------------------------------------------------------ styles
  var style = document.createElement('style');
  style.textContent = [
    '#rapfi-db-fab{position:fixed;right:12px;bottom:84px;z-index:2147483000;',
    'width:48px;height:48px;border-radius:50%;background:#2c3e50;color:#fff;',
    'display:flex;align-items:center;justify-content:center;font:600 13px sans-serif;',
    'box-shadow:0 2px 8px rgba(0,0,0,.35);opacity:.85;cursor:pointer;user-select:none}',
    '#rapfi-db-fab:active{opacity:1}',
    '#rapfi-db-overlay{position:fixed;inset:0;z-index:2147483001;background:rgba(0,0,0,.45);',
    'display:none;align-items:center;justify-content:center;padding:16px}',
    '#rapfi-db-overlay.open{display:flex}',
    '#rapfi-db-card{background:#fff;color:#222;border-radius:12px;width:100%;max-width:440px;',
    'max-height:84vh;overflow:auto;padding:16px;font:14px/1.5 sans-serif;',
    'box-shadow:0 8px 32px rgba(0,0,0,.35)}',
    '#rapfi-db-card h3{margin:0 0 10px;font-size:16px;display:flex;justify-content:space-between}',
    '#rapfi-db-card .sec{margin:12px 0 4px;font-weight:600;color:#2c3e50}',
    '#rapfi-db-card .row{display:flex;gap:8px;align-items:center;margin:6px 0}',
    '#rapfi-db-card label{display:flex;gap:8px;align-items:center;margin:6px 0;cursor:pointer}',
    '#rapfi-db-card .muted{color:#777;font-size:12px;white-space:pre-wrap;word-break:break-all}',
    '#rapfi-db-card select,#rapfi-db-card input{flex:1;padding:8px;border:1px solid #ccc;',
    'border-radius:8px;font-size:14px;background:#fff;color:#222;min-width:0}',
    '#rapfi-db-card button{padding:8px 12px;border:0;border-radius:8px;background:#2c3e50;',
    'color:#fff;font-size:14px;cursor:pointer;white-space:nowrap}',
    '#rapfi-db-card button.ghost{background:#e8e8e8;color:#333}',
    '#rapfi-db-card button.danger{background:#c0392b}',
    '#rapfi-db-card .close{background:none;color:#888;font-size:20px;padding:0 6px}',
    '#rapfi-db-msg{min-height:18px;color:#2c7a39;font-size:12px}',
    '#rapfi-db-msg.err{color:#c0392b}'
  ].join('');
  document.head.appendChild(style);

  // ------------------------------------------------------------------- markup
  var fab = document.createElement('div');
  fab.id = 'rapfi-db-fab';
  fab.textContent = 'DB';
  fab.title = 'Rapfi 设置';

  var overlay = document.createElement('div');
  overlay.id = 'rapfi-db-overlay';

  var card = document.createElement('div');
  card.id = 'rapfi-db-card';
  overlay.appendChild(card);
  card.innerHTML = [
    '<h3><span>Rapfi 设置 / Database</span><button class="close" data-act="close">×</button></h3>',
    '<div class="muted" id="rapfi-db-current"></div>',
    '<div class="sec">使用方式</div>',
    '<div id="rapfi-db-modes"></div>',
    '<div class="sec">切换数据库</div>',
    '<div class="row"><select id="rapfi-db-select"></select>',
    '<button data-act="switch">切换</button>',
    '<button class="danger" data-act="delete">删除</button></div>',
    '<div class="sec">新建数据库</div>',
    '<div class="row"><input id="rapfi-db-name" type="text" placeholder="输入名称，如 game1" maxlength="64">',
    '<button data-act="create">创建</button></div>',
    '<div class="muted">名称不能包含 . / \\ 等字符。新建后会自动切换到该库。</div>',
    '<div class="sec">引擎评估 / Engine</div>',
    '<div class="muted" id="rapfi-db-engine">读取中…</div>',
    '<div id="rapfi-db-msg"></div>'
  ].join('');

  var modesBox = card.querySelector('#rapfi-db-modes');
  MODES.forEach(function (m) {
    var label = document.createElement('label');
    var input = document.createElement('input');
    input.type = 'radio';
    input.name = 'rapfi-db-mode';
    input.value = m.key;
    label.appendChild(input);
    var span = document.createElement('span');
    span.textContent = m.label;
    label.appendChild(span);
    var hint = document.createElement('span');
    hint.className = 'muted';
    hint.textContent = '（' + m.hint + '）';
    label.appendChild(hint);
    modesBox.appendChild(label);
  });

  var msgEl = card.querySelector('#rapfi-db-msg');
  var currentEl = card.querySelector('#rapfi-db-current');
  var engineEl = card.querySelector('#rapfi-db-engine');
  var selectEl = card.querySelector('#rapfi-db-select');
  var nameEl = card.querySelector('#rapfi-db-name');

  function setMessage(text, isError) {
    msgEl.textContent = text || '';
    msgEl.className = isError ? 'err' : '';
  }

  // ----------------------------------------------------------------- render
  function render() {
    currentEl.textContent = '当前库：' + state.current
      + (state.recordCount >= 0 ? '（已缓存 ' + state.recordCount + ' 个局面）' : '（记录数未知）');

    var inputs = modesBox.querySelectorAll('input');
    for (var i = 0; i < inputs.length; i++)
      inputs[i].checked = inputs[i].value === state.mode;

    selectEl.innerHTML = '';
    state.databases.forEach(function (name) {
      var option = document.createElement('option');
      option.value = name;
      option.textContent = name + (name === state.current ? '  ✓' : '');
      option.selected = name === state.current;
      selectEl.appendChild(option);
    });
  }

  function renderEngine(status) {
    if (!status) { engineEl.textContent = '引擎状态不可用'; return; }
    var lines = [];
    lines.push('评估器: ' + (status.evaluator || '?')
               + (status.nnue ? '  ✓ 已启用' : '  ✗ 未启用(回退经典)'));
    lines.push('config: ' + (status.configPath || '(无)'));
    (status.assets || []).forEach(function (line) { lines.push(line); });
    engineEl.textContent = lines.join('\n');
  }

  function adopt(result) {
    if (!result) return;
    state.mode = result.mode || state.mode;
    state.current = result.current || result.name || state.current;
    state.databases = (result.databases && result.databases.length)
      ? result.databases : state.databases;
    state.recordCount = typeof result.recordCount === 'number' ? result.recordCount : -1;
    render();
  }

  function apply(promise) {
    setMessage('处理中…');
    return promise
      .then(function (result) { adopt(result); setMessage('已更新'); })
      .catch(function (error) { setMessage('失败：' + (error && error.message || error), true); });
  }

  function refresh() {
    api('getDatabaseStatus')
      .then(function (result) { adopt(result); })
      .catch(function () { /* keep last known state */ });
    api('getEngineStatus')
      .then(renderEngine)
      .catch(function () { /* ignore */ });
  }

  // ---------------------------------------------------------------- events
  fab.addEventListener('click', function () {
    overlay.classList.add('open');
    setMessage('');
    refresh();
  });

  overlay.addEventListener('click', function (event) {
    if (event.target === overlay) overlay.classList.remove('open');
  });

  card.addEventListener('click', function (event) {
    var action = event.target && event.target.getAttribute
      ? event.target.getAttribute('data-act') : null;
    if (action === 'close') { overlay.classList.remove('open'); return; }
    if (action === 'switch') {
      apply(api('switchDatabase', { name: selectEl.value }));
      return;
    }
    if (action === 'delete') {
      if (window.confirm('确定删除数据库 “' + selectEl.value + '” 吗？'))
        apply(api('deleteDatabase', { name: selectEl.value }));
      return;
    }
    if (action === 'create') {
      var name = (nameEl.value || '').trim();
      if (!name) { setMessage('请输入名称', true); return; }
      apply(api('createDatabase', { name: name })).then(function () {
        nameEl.value = '';
      });
    }
  });

  modesBox.addEventListener('change', function (event) {
    if (event.target && event.target.value)
      apply(api('setDatabaseMode', { mode: event.target.value }));
  });

  function mount() {
    document.body.appendChild(fab);
    document.body.appendChild(overlay);
    refresh();
  }

  window.__rapfiPanel = {
    refresh: refresh,
    open: function () { overlay.classList.add('open'); refresh(); }
  };

  if (document.body)
    mount();
  else if (document.addEventListener)
    document.addEventListener('DOMContentLoaded', mount);
})();
