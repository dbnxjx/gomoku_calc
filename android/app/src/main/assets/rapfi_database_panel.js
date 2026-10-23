/*
 * Rapfi analysis-database settings for the packaged Web UI.
 *
 * Injected at runtime by NativeRapfiPlugin. It appends one weui-styled card to the page's
 * Settings view (div.settings) so it matches the surrounding groups (the same .weui-cells /
 * .weui-cell markup the vux components render), and re-appends it when Vue re-renders the view.
 *
 * NNUE is NOT configured here: the page's existing "引擎权重" selector already offers
 * Mix9svq NNUE / 传统(2022/07/23) / 传统(2021/09/01). Those options are hidden behind the
 * store's fullEngine flag, which this script turns on (when the bundled weights are available)
 * so the NNUE option becomes selectable. The plugin maps the resulting RELOADCONFIG names onto the
 * extracted configs.
 *
 * Plugin methods used: getDatabaseStatus / setDatabaseMode / switchDatabase / createDatabase /
 * deleteDatabase / exportDatabase / importDatabase / getEngineStatus.
 */
(function () {
  'use strict';

  var BLOCK_ID = 'rapfi-settings';
  var STYLE_ID = 'rapfi-settings-style';

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

  function errText(error) {
    return (error && (error.message || error)) || '未知错误';
  }

  var state = {
    mode: 'on',
    current: 'default',
    databases: ['default'],
    recordCount: -1,
    nnueAvailable: null      // null = not queried yet
  };

  // ---------------------------------------------------------------- styles
  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '.rapfi-select{border:0;background:transparent;color:#888;font-size:14px;',
      'text-align:right;padding:0;max-width:55vw;}',
      '.rapfi-input{border:0;background:transparent;color:#888;font-size:14px;',
      'text-align:right;width:110px;padding:0;}',
      '.rapfi-link{color:#586c94;font-size:14px;padding-left:14px;}',
      '.rapfi-danger{color:#e64340;}',
      '.rapfi-msg{color:#888;font-size:12px;white-space:pre-wrap;word-break:break-all;}'
    ].join('');
    document.head.appendChild(style);
  }

  // ---------------------------------------------------------------- markup
  function cell(label, right) {
    var row = document.createElement('div');
    row.className = 'weui-cell';
    var bd = document.createElement('div');
    bd.className = 'weui-cell__bd';
    bd.textContent = label;
    var ft = document.createElement('div');
    ft.className = 'weui-cell__ft';
    ft.appendChild(right);
    row.appendChild(bd);
    row.appendChild(ft);
    return row;
  }

  function buildCard() {
    var card = document.createElement('div');
    card.className = 'weui-cells';
    card.id = BLOCK_ID;

    var title = document.createElement('div');
    title.className = 'weui-cells__title';
    title.textContent = '分析数据库';
    card.appendChild(title);

    var mode = document.createElement('select');
    mode.className = 'rapfi-select';
    mode.id = 'rapfi-db-mode';
    [['on', '使用数据库'], ['readonly', '只读数据库'], ['off', '不使用数据库']]
      .forEach(function (pair) {
        var option = document.createElement('option');
        option.value = pair[0];
        option.textContent = pair[1];
        mode.appendChild(option);
      });
    mode.addEventListener('change', function () {
      busy('处理中…');
      api('setDatabaseMode', { mode: mode.value })
        .then(function (result) { adopt(result); done('已更新'); })
        .catch(function (error) { fail('设置失败', error); });
    });
    card.appendChild(cell('使用方式', mode));

    var current = document.createElement('span');
    current.id = 'rapfi-db-current';
    card.appendChild(cell('当前数据库', current));

    var select = document.createElement('select');
    select.className = 'rapfi-select';
    select.id = 'rapfi-db-select';
    select.addEventListener('change', function () {
      busy('切换中…');
      api('switchDatabase', { name: select.value })
        .then(function (result) { adopt(result); done('已切换'); })
        .catch(function (error) { fail('切换失败', error); });
    });
    card.appendChild(cell('切换数据库', select));

    var name = document.createElement('input');
    name.className = 'rapfi-input';
    name.id = 'rapfi-db-name';
    name.type = 'text';
    name.placeholder = '名称';
    name.maxLength = 64;
    var create = document.createElement('a');
    create.className = 'rapfi-link';
    create.textContent = '创建';
    create.addEventListener('click', function () {
      var value = (name.value || '').trim();
      if (!value) { fail('请输入名称', null); return; }
      busy('创建中…');
      api('createDatabase', { name: value })
        .then(function (result) { name.value = ''; adopt(result); done('已创建'); })
        .catch(function (error) { fail('创建失败', error); });
    });
    var createBox = document.createElement('div');
    createBox.appendChild(name);
    createBox.appendChild(create);
    card.appendChild(cell('新建数据库', createBox));

    var importLink = document.createElement('a');
    importLink.className = 'rapfi-link';
    importLink.textContent = '导入';
    importLink.addEventListener('click', function () {
      busy('请选择 .db 文件…');
      api('importDatabase', {})
        .then(function (result) { adopt(result); done('已导入'); })
        .catch(function (error) { fail('导入失败', error); });
    });
    card.appendChild(cell('导入 .db 文件', importLink));

    var exportLink = document.createElement('a');
    exportLink.className = 'rapfi-link';
    exportLink.textContent = '导出';
    exportLink.addEventListener('click', function () {
      busy('请选择保存位置…');
      api('exportDatabase', { name: state.current })
        .then(function () { done('已导出'); })
        .catch(function (error) { fail('导出失败', error); });
    });
    card.appendChild(cell('导出当前数据库', exportLink));

    var deleteLink = document.createElement('a');
    deleteLink.className = 'rapfi-link rapfi-danger';
    deleteLink.textContent = '删除';
    deleteLink.addEventListener('click', function () {
      if (!window.confirm('确定删除数据库 “' + state.current + '” 吗？')) return;
      busy('删除中…');
      api('deleteDatabase', { name: state.current })
        .then(function (result) {
          adopt(result);
          done(result && result.reset ? '已清空（唯一数据库）' : '已删除');
        })
        .catch(function (error) { fail('删除失败', error); });
    });
    card.appendChild(cell('删除当前数据库', deleteLink));

    var msgRow = document.createElement('div');
    msgRow.className = 'weui-cell';
    var msgBd = document.createElement('div');
    msgBd.className = 'weui-cell__bd';
    var msg = document.createElement('span');
    msg.className = 'rapfi-msg';
    msg.id = 'rapfi-msg';
    msgBd.appendChild(msg);
    msgRow.appendChild(msgBd);
    card.appendChild(msgRow);

    return card;
  }

  // -------------------------------------------------------------- rendering
  function busy(text) { setMessage(text, false); }
  function done(text) { setMessage(text, false); }
  function fail(prefix, error) {
    setMessage(prefix + (error ? '：' + errText(error) : ''), true);
  }

  function setMessage(text, isError) {
    var msg = document.getElementById('rapfi-msg');
    if (!msg) return;
    msg.textContent = text || '';
    msg.style.color = isError ? '#e64340' : '#888';
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

  function render() {
    var mode = document.getElementById('rapfi-db-mode');
    if (mode) mode.value = state.mode;

    var current = document.getElementById('rapfi-db-current');
    if (current) {
      current.textContent = state.recordCount >= 0
        ? state.current + '（' + state.recordCount + ' 条）'
        : state.current;
    }

    var select = document.getElementById('rapfi-db-select');
    if (select) {
      select.innerHTML = '';
      state.databases.forEach(function (name) {
        var option = document.createElement('option');
        option.value = name;
        option.textContent = name;
        option.selected = name === state.current;
        select.appendChild(option);
      });
    }
  }

  function refresh() {
    api('getDatabaseStatus').then(adopt).catch(function () { });
    api('getEngineStatus').then(function (status) {
      if (status && typeof status.nnueAvailable === 'boolean')
        state.nnueAvailable = status.nnueAvailable;
    }).catch(function () { });
  }

  // -------------------------------------------- expose NNUE in "引擎权重"
  /**
   * The page hides the "Mix9svq NNUE" weight option behind its fullEngine store flag (it is set
   * to false after the engine starts). Flip it back on once, when the bundled weights exist, so
   * NNUE becomes one of the normal weight choices instead of a separate setting.
   */
  function exposeNnueWeightOption() {
    if (state.nnueAvailable === false) return;
    try {
      var app = document.querySelector('#app');
      var vm = app && app.__vue__;
      var store = vm && vm.$store;
      if (!store || !store.state || !store.state.ai) return;
      if (store.state.ai.fullEngine !== true)
        store.commit('ai/setFullEngine', true);
    } catch (error) {
      // ignore: the option simply stays hidden
    }
  }

  // ------------------------------------------------------------ injection loop
  function ensureInjected() {
    exposeNnueWeightOption();
    var settings = document.querySelector('.settings');
    if (!settings) return;
    if (!document.getElementById(BLOCK_ID)) {
      ensureStyle();
      settings.appendChild(buildCard());
      render();
      refresh();
    }
  }

  ensureInjected();
  if (document.addEventListener)
    document.addEventListener('DOMContentLoaded', ensureInjected);
  window.setInterval(ensureInjected, 700);

  window.__rapfiSettings = { refresh: refresh, ensureInjected: ensureInjected };
})();
