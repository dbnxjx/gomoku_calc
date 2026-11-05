<template>
  <div class="settings">
    <group>
      <group-title slot="title">{{ $t('setting.engine.title') }}</group-title>
      <popup-radio :title="$t('setting.engine.select')" v-model="engineTypeValue" :options="engineOptions" />
      <cell :title="$t('setting.engine.rapfi')">
        {{ nativeRapfiAvailable ? $t('setting.engine.available') : $t('setting.engine.unavailable') }}
      </cell>
      <cell :title="$t('setting.engine.katago')">
        {{ nativeKataGoAvailable ? $t('setting.engine.available') : $t('setting.engine.unavailable') }}
      </cell>
    </group>

    <group>
      <popup-radio :title="$t('setting.language')" v-model="languageValue" :options="languageOptions" />
      <popup-radio :title="$t('setting.layout.title')" v-model="layoutModeValue" :options="layoutModeOptions" />
    </group>

    <group v-if="engineType === 'RAPFI'" key="rapfi-database">
      <group-title slot="title">分析数据库</group-title>
      <popup-radio title="使用方式" v-model="databaseModeValue" :options="databaseModeOptions" />
      <cell title="当前数据库" is-link @click.native="databasePickerOpen = true">
        {{ databaseCurrent }}<span v-if="databaseRecordCount >= 0">（{{ databaseRecordCount }} 条）</span>
      </cell>
    </group>

    <group v-if="engineType === 'RAPFI'" key="rapfi-time">
      <group-title slot="title">{{ $t('setting.thinking.timeTitle') }}</group-title>
      <radio :options="thinkTimeOptions" v-model="thinkTimeSelected" />
      <div class="time-settings" v-if="thinkTimeSelected == 0">
        <x-number :title="$t('setting.thinking.turnTime')" v-model="turnTimeInSecond" button-style="round" width="75px"
          :min="0.5" :max="99999" :step="turnTimeInSecond < 5 ? 0.5 : 1" fillable />
        <x-number :title="$t('setting.thinking.matchTime')" v-model="matchTimeInSecond" button-style="round"
          width="75px" :min="1" :max="99999" fillable />
        <x-number :title="$t('setting.thinking.maxDepth')" v-model="maxDepthValue" button-style="round" width="75px"
          :min="4" :max="64" fillable />
        <x-number :title="$t('setting.thinking.maxNode')" v-model="maxNodeValue" button-style="round" width="75px"
          :min="0" :max="99999" :step="0.1" fillable />
      </div>
    </group>

    <group v-if="engineType === 'RAPFI'" key="rapfi-board">
      <group-title slot="title">{{ $t('setting.board.title') }}</group-title>
      <x-number :title="$t('setting.board.size')" v-model="boardSizeValue" button-style="round" width="75px" :min="5"
        :max="22" fillable />
      <popup-radio :title="$t('setting.board.rule.title')" v-model="ruleValue" :options="ruleOptions" />
      <x-switch :title="$t('setting.board.aiThinkBlack')" v-model="aiThinkBlackValue" />
      <x-switch :title="$t('setting.board.aiThinkWhite')" v-model="aiThinkWhiteValue" />
    </group>

    <group v-if="engineType === 'RAPFI'" key="rapfi-search">
      <group-title slot="title">{{ $t('setting.thinking.thinkTitle') }}</group-title>
      <x-number :title="$t('setting.thinking.nbest')" v-model="nbestValue" button-style="round" width="75px" :min="1"
        :max="boardSizeValue * boardSizeValue" fillable />
      <x-number :title="$t('setting.thinking.handicap')" v-model="handicapValue" button-style="round" width="75px"
        :min="0" :max="100" :step="5" fillable />
      <x-number :title="$t('setting.thinking.rapfiThreads')" v-model="threadsValue"
        button-style="round" width="75px" :min="1" :max="maxThreads" fillable />
      <x-switch :title="$t('setting.thinking.pondering')" v-model="ponderingValue" />
      <popup-radio :title="$t('setting.thinking.config.title')" v-model="configIndexValue"
        :options="configIndexOptions" />
      <popup-radio :title="$t('setting.thinking.candrange.title')" v-model="candRangeValue"
        :options="candRangeOptions" />
      <popup-radio :title="$t('setting.thinking.hashSize')" v-model="hashSizeValue" :options="hashSizeOptions" />
    </group>

    <group key="board-display">
      <group-title slot="title">{{ $t('setting.board.showTitle') }}</group-title>
      <popup-radio v-if="engineType === 'RAPFI'" :title="$t('setting.board.clickCheck.title')" v-model="clickCheckValue"
        :options="clickCheckOptions" />
      <x-switch v-if="engineType === 'RAPFI'" :title="$t('setting.board.showCoord')" v-model="showCoordValue" />
      <x-switch :title="$t('setting.board.showAnalysis')" v-model="showAnalysisValue" />
      <x-switch :title="$t('setting.board.showMoveTree')" v-model="showMoveTreeValue" />
      <x-switch v-if="engineType === 'RAPFI'" :title="$t('setting.board.showForbid')" v-model="showForbidValue" />
      <x-switch v-if="engineType === 'RAPFI'" :title="$t('setting.board.showKillPoints')" v-model="showKillPointsValue" />
      <popup-radio v-if="engineType === 'RAPFI'" :title="$t('setting.board.showPvEval.title')" v-model="showPvEvalValue"
        :options="showPvEvalOptions" />
    </group>

    <group v-if="engineType === 'RAPFI'" key="rapfi-colors">
      <group-title slot="title">{{ $t('setting.board.colorTitle') }}</group-title>
      <cell :title="$t('setting.board.boardColor')">
        <input type="color" v-model="boardColor" />
      </cell>
      <cell :title="$t('setting.board.lastStepColor')">
        <input type="color" v-model="lastStepColor" />
      </cell>
      <cell :title="$t('setting.board.bestMoveColor')">
        <input type="color" v-model="bestMoveColor" />
      </cell>
      <cell :title="$t('setting.board.thoughtMoveColor')">
        <input type="color" v-model="thoughtMoveColor" />
      </cell>
      <cell :title="$t('setting.board.lostMoveColor')">
        <input type="color" v-model="lostMoveColor" />
      </cell>
    </group>

    <group v-if="engineType === 'KATAGO'" key="katago-settings">
      <group-title slot="title">KataGo</group-title>
      <x-number title="显示候选点数" v-model="kataGoMaxPointsValue" button-style="round" width="75px" :min="1"
        :max="225" fillable />
      <x-switch :title="$t('setting.board.showHeatmap')" v-model="showHeatmapValue" />
      <box gap="8px 16px 16px">
        <x-button type="primary" :disabled="kataGoBenchmarking || !nativeKataGoAvailable"
          @click.native="runKataGoBenchmark">
          {{ kataGoBenchmarking ? '正在测速…' : '自动测速并保存' }}
        </x-button>
      </box>
    </group>

    <box gap="40px 20px 20px 20px">
      <x-button type="warn" @click.native="reset">{{ $t('setting.reset') }}</x-button>
    </box>

    <!-- Keep a real child here: transfer-dom moves its element to body, so it
         cannot be an insertion anchor among the conditional settings groups. -->
    <div key="database-dialogs">
      <div v-transfer-dom>
        <popup v-model="databasePickerOpen" position="bottom" height="auto">
          <div class="database-picker">
            <div v-for="name in databasePickerNames" :key="name" class="database-picker-row">
              <button class="database-picker-name" @click="onDatabaseNameClick(name)"
                @mousedown="startDatabaseLongPress(name)" @mouseup="cancelDatabaseLongPress"
                @mouseleave="cancelDatabaseLongPress" @touchstart="startDatabaseLongPress(name)"
                @touchmove="cancelDatabaseLongPress" @touchend="cancelDatabaseLongPress"
                @touchcancel="cancelDatabaseLongPress">{{ name }}</button>
              <button class="database-action database-export" @click="exportDatabase(name)">导出</button>
              <span v-if="name === databaseCurrent" class="database-action database-check">✓</span>
              <button v-else class="database-action database-delete" @click="deleteDatabase(name)">删除</button>
            </div>
            <div class="database-picker-action-row database-picker-first-action" @click="importDatabase">导入已有数据库</div>
            <div class="database-picker-action-row" @click="openCreateDatabase">新建数据库</div>
          </div>
        </popup>
      </div>

      <div v-transfer-dom>
        <x-dialog v-model="createDatabaseDialog" hide-on-blur>
          <div class="database-create-dialog">
            <div class="database-create-title">新建数据库</div>
            <input ref="databaseNameInput" v-model="newDatabaseName" maxlength="64" autofocus placeholder="名称" />
            <div class="database-create-buttons">
              <button @click="createDatabaseDialog = false">取消</button>
              <button class="database-confirm" @click="createDatabase">确定</button>
            </div>
          </div>
        </x-dialog>
      </div>
    </div>
  </div>
</template>

<script>
import { TransferDom, Group, GroupTitle, XSwitch, XNumber, XButton, Radio, PopupRadio, Cell, Box, Popup, XDialog } from 'vux'
import { registerPlugin } from '@capacitor/core'
import { mapState, mapMutations, mapActions } from 'vuex'
import { THINK_TIME_CUSTOM, THINK_TIME_ANALYSIS, LAYOUT_LANDSCAPE, LAYOUT_PORTRAIT, LAYOUT_AUTO } from '@/store/modules/settings'

const NativeRapfi = registerPlugin('NativeRapfi')
const DATABASE_STATUS_INTERVAL = 1000
const DATABASE_LONG_PRESS_MS = 650

export default {
  name: 'settings',
  directives: { TransferDom },
  components: {
    Group,
    GroupTitle,
    XSwitch,
    XNumber,
    XButton,
    Radio,
    PopupRadio,
    Cell,
    Box,
    Popup,
    XDialog,
  },
  data: function () {
    return {
      showLanguageOption: false,
      databaseMode: 'on',
      databaseCurrent: 'default',
      databaseNames: ['default'],
      databaseRecordCount: -1,
      newDatabaseName: '',
      databaseBusy: false,
      databasePickerOpen: false,
      createDatabaseDialog: false,
      databaseStatusTimer: null,
      databaseStatusLoading: false,
      databaseLongPressTimer: null,
      databaseLongPressHandled: false,
    }
  },
  computed: {
    ...mapState(['maxThreads', 'supportThreads', 'supportSimd', 'supportRelaxedSimd']),
    ...mapState('ai', ['fullEngine', 'nativeRapfiAvailable', 'nativeKataGoAvailable', 'kataGoBenchmarking']),
    ...mapState('settings', [
      'language',
      'layoutMode',
      'thinkTimeOption',
      'turnTime',
      'matchTime',
      'maxDepth',
      'maxNodes',
      'strength',
      'nbest',
      'threads',
      'rapfiThreads',
      'engineType',
      'pondering',
      'configIndex',
      'candRange',
      'boardSize',
      'rule',
      'aiThinkBlack',
      'aiThinkWhite',
      'clickCheck',
      'showCoord',
      'showAnalysis',
      'showMoveTree',
      'showPvEval',
      'showForbid',
      'showHeatmap',
      'showKillPoints',
      'kataGoMaxPoints',
      'boardStyle',
      'hashSize',
    ]),
    languageOptions() {
      return [
        { "key": "zh-CN", "value": "简体中文" },
        { "key": "zh-TW", "value": "繁體中文" },
        { "key": "en", "value": "English" },
        { "key": "ko", "value": "한국어" },
        { "key": "ja", "value": "日本語" },
        { "key": "vi", "value": "Tiếng Việt" },
        { "key": "ru", "value": "Русский" }
      ]
    },
    layoutModeOptions() {
      return [
        { key: LAYOUT_LANDSCAPE, value: this.$t('setting.layout.landscape') },
        { key: LAYOUT_PORTRAIT, value: this.$t('setting.layout.portrait') },
        { key: LAYOUT_AUTO, value: this.$t('setting.layout.auto') },
      ]
    },
    engineOptions() {
      return [
        { key: 'RAPFI', value: 'Rapfi' },
        { key: 'KATAGO', value: 'KataGo' },
      ]
    },
    thinkTimeOptions() {
      return [
        { key: THINK_TIME_ANALYSIS, value: this.$t('setting.thinking.analysis') },
        { key: THINK_TIME_CUSTOM, value: this.$t('setting.thinking.custom') },
      ]
    },
    configIndexOptions() {
      if (this.fullEngine || this.engineType === 'RAPFI') {
        return [
          { key: 0, value: this.$t('setting.thinking.config.mix9svq') },
          { key: 1, value: this.$t('setting.thinking.config.220723') },
          { key: 2, value: this.$t('setting.thinking.config.210901') },
        ]
      } else {
        return [
          { key: 1, value: this.$t('setting.thinking.config.220723') },
          { key: 2, value: this.$t('setting.thinking.config.210901') },
        ]
      }
    },
    candRangeOptions() {
      return [
        { key: 0, value: this.$t('setting.thinking.candrange.square2') },
        { key: 1, value: this.$t('setting.thinking.candrange.square2line3') },
        { key: 2, value: this.$t('setting.thinking.candrange.square3') },
        { key: 3, value: this.$t('setting.thinking.candrange.square3line4') },
        { key: 4, value: this.$t('setting.thinking.candrange.square4') },
        { key: 5, value: this.$t('setting.thinking.candrange.fullboard') },
      ]
    },
    hashSizeOptions() {
      return [
        { key: 128, value: '128MB' },
        { key: 256, value: '256MB' },
        { key: 512, value: '512MB' },
        { key: 1024, value: '1GB' },
        { key: 1536, value: '1.5GB' },
      ]
    },
    ruleOptions() {
      return [
        { key: 0, value: this.$t('setting.board.rule.gomoku') },
        { key: 1, value: this.$t('setting.board.rule.standard') },
        { key: 2, value: this.$t('setting.board.rule.renju') },
        { key: 5, value: this.$t('setting.board.rule.swap1') },
      ]
    },
    clickCheckOptions() {
      return [
        { key: 0, value: this.$t('setting.board.clickCheck.direct') },
        { key: 1, value: this.$t('setting.board.clickCheck.confirm') },
        { key: 2, value: this.$t('setting.board.clickCheck.slide') },
      ]
    },
    showPvEvalOptions() {
      return [
        { key: 0, value: this.$t('setting.board.showPvEval.none') },
        { key: 1, value: this.$t('setting.board.showPvEval.eval') },
        { key: 2, value: this.$t('setting.board.showPvEval.winrate') },
        // { key: 3, value: this.$t('setting.board.showPvEval.evalAndDepth') },
      ]
    },
    languageValue: {
      get() {
        return this.language
      },
      set(v) {
        this.setValue({ key: 'language', value: v })
      },
    },
    layoutModeValue: {
      get() {
        return this.layoutMode
      },
      set(v) {
        this.setValue({ key: 'layoutMode', value: v })
      },
    },
    thinkTimeSelected: {
      get() {
        return this.thinkTimeOption
      },
      set(v) {
        this.setValue({ key: 'thinkTimeOption', value: v })
      },
    },
    turnTimeInSecond: {
      get() {
        return Math.floor(this.turnTime / 500) / 2
      },
      set(v) {
        this.setValue({ key: 'turnTime', value: v * 1000 })
      },
    },
    matchTimeInSecond: {
      get() {
        return Math.floor(this.matchTime / 1000)
      },
      set(v) {
        this.setValue({ key: 'matchTime', value: v * 1000 })
      },
    },
    maxDepthValue: {
      get() {
        return this.maxDepth
      },
      set(v) {
        this.setValue({ key: 'maxDepth', value: v })
      },
    },
    maxNodeValue: {
      get() {
        return this.maxNodes / 1000000
      },
      set(v) {
        this.setValue({ key: 'maxNodes', value: Math.ceil(v * 1000000) })
      },
    },
    handicapValue: {
      get() {
        return 100 - this.strength
      },
      set(v) {
        this.setValue({ key: 'strength', value: 100 - v })
      },
    },
    nbestValue: {
      get() {
        return this.nbest
      },
      set(v) {
        this.setValue({ key: 'nbest', value: v })
      },
    },
    kataGoMaxPointsValue: {
      get() {
        return this.kataGoMaxPoints
      },
      set(v) {
        this.setValue({ key: 'kataGoMaxPoints', value: Math.max(1, Math.min(225, Number(v) || 1)) })
      },
    },
    databaseModeOptions() {
      return [
        { key: 'on', value: '使用数据库' },
        { key: 'readonly', value: '只读数据库' },
        { key: 'off', value: '不使用数据库' },
      ]
    },
    databasePickerNames() {
      return [this.databaseCurrent].concat(this.databaseNames.filter((name) => name !== this.databaseCurrent))
    },
    databaseModeValue: {
      get() {
        return this.databaseMode
      },
      set(value) {
        this.setDatabaseMode(value)
      },
    },
    threadsValue: {
      get() {
        return this.rapfiThreads || this.threads || 1
      },
      set(v) {
        this.setValue({ key: 'rapfiThreads', value: v })
        this.setValue({ key: 'threads', value: v })
      },
    },
    ponderingValue: {
      get() {
        return this.engineType === 'RAPFI' ? this.pondering : false
      },
      set(v) {
        this.setValue({ key: 'pondering', value: v })
      },
    },
    configIndexValue: {
      get() {
        return this.configIndex
      },
      set(v) {
        this.setValue({ key: 'configIndex', value: v })
      },
    },
    candRangeValue: {
      get() {
        return this.candRange
      },
      set(v) {
        this.setValue({ key: 'candRange', value: v })
      },
    },
    hashSizeValue: {
      get() {
        return this.hashSize
      },
      set(v) {
        this.setValue({ key: 'hashSize', value: v })
      },
    },
    boardSizeValue: {
      get() {
        return this.boardSize
      },
      set(v) {
        this.setValue({ key: 'boardSize', value: v })
      },
    },
    ruleValue: {
      get() {
        return this.rule
      },
      set(v) {
        this.setValue({ key: 'rule', value: v })
        this.checkForbid()
      },
    },
    aiThinkBlackValue: {
      get() {
        return this.aiThinkBlack
      },
      set(v) {
        this.setValue({ key: 'aiThinkBlack', value: v })
      },
    },
    aiThinkWhiteValue: {
      get() {
        return this.aiThinkWhite
      },
      set(v) {
        this.setValue({ key: 'aiThinkWhite', value: v })
      },
    },
    clickCheckValue: {
      get() {
        return this.clickCheck
      },
      set(v) {
        this.setValue({ key: 'clickCheck', value: v })
      },
    },
    showCoordValue: {
      get() {
        return this.showCoord
      },
      set(v) {
        this.setValue({ key: 'showCoord', value: v })
      },
    },
    showAnalysisValue: {
      get() {
        return this.showAnalysis
      },
      set(v) {
        this.setValue({ key: 'showAnalysis', value: v })
      },
    },
    showMoveTreeValue: {
      get() {
        return this.showMoveTree
      },
      set(v) {
        this.setValue({ key: 'showMoveTree', value: v })
      },
    },
    showPvEvalValue: {
      get() {
        return this.showPvEval
      },
      set(v) {
        this.setValue({ key: 'showPvEval', value: v })
      },
    },
    showForbidValue: {
      get() {
        return this.showForbid
      },
      set(v) {
        this.setValue({ key: 'showForbid', value: v })
      },
    },
    engineTypeValue: {
      get() {
        return this.engineType
      },
      set(v) {
        this.setValue({ key: 'engineType', value: v })
      },
    },
    showHeatmapValue: {
      get() {
        return this.showHeatmap
      },
      set(v) {
        this.setValue({ key: 'showHeatmap', value: v })
      },
    },
    showKillPointsValue: {
      get() {
        return this.showKillPoints
      },
      set(v) {
        this.setValue({ key: 'showKillPoints', value: v })
      },
    },
    boardColor: {
      get() {
        return this.boardStyle.boardColor
      },
      set(v) {
        this.setBoardStyle({ key: 'boardColor', value: v })
      },
    },
    lastStepColor: {
      get() {
        return this.boardStyle.lastStepColor
      },
      set(v) {
        this.setBoardStyle({ key: 'lastStepColor', value: v })
      },
    },
    bestMoveColor: {
      get() {
        return this.boardStyle.bestMoveColor
      },
      set(v) {
        this.setBoardStyle({ key: 'bestMoveColor', value: v })
      },
    },
    thoughtMoveColor: {
      get() {
        return this.boardStyle.thoughtMoveColor
      },
      set(v) {
        this.setBoardStyle({ key: 'thoughtMoveColor', value: v })
      },
    },
    lostMoveColor: {
      get() {
        return this.boardStyle.lostMoveColor
      },
      set(v) {
        this.setBoardStyle({ key: 'lostMoveColor', value: v })
      },
    },
  },
  methods: {
    ...mapMutations('settings', ['setValue', 'setBoardStyle']),
    ...mapActions('settings', ['clearCookies']),
    ...mapActions('ai', ['checkForbid', 'runKataGoBenchmark']),
    async refreshDatabaseStatus() {
      if (this.engineType !== 'RAPFI' || !NativeRapfi.getDatabaseStatus) return
      if (this.databaseBusy || this.databaseStatusLoading) return
      this.databaseStatusLoading = true
      try {
        const result = await NativeRapfi.getDatabaseStatus()
        if (!this.databaseBusy) this.adoptDatabaseStatus(result)
      } catch (error) {
        // The native plugin may still be starting; the next poll refreshes it.
      } finally {
        this.databaseStatusLoading = false
      }
    },
    startDatabaseStatusPolling() {
      this.stopDatabaseStatusPolling()
      this.refreshDatabaseStatus()
      this.databaseStatusTimer = setInterval(() => this.refreshDatabaseStatus(), DATABASE_STATUS_INTERVAL)
    },
    stopDatabaseStatusPolling() {
      clearInterval(this.databaseStatusTimer)
      this.databaseStatusTimer = null
    },
    adoptDatabaseStatus(result) {
      if (!result) return
      if (result.mode) this.databaseMode = result.mode
      if (result.current || result.name) this.databaseCurrent = result.current || result.name
      if (Array.isArray(result.databases) && result.databases.length) this.databaseNames = result.databases
      if (typeof result.recordCount === 'number') this.databaseRecordCount = result.recordCount
    },
    adoptChangedDatabase(result) {
      this.adoptDatabaseStatus(result)
      this.$store.commit('ai/bumpDatabaseRevision')
    },
    onDatabaseNameClick(name) {
      if (this.databaseLongPressHandled) {
        this.databaseLongPressHandled = false
        return
      }
      this.switchDatabase(name)
    },
    startDatabaseLongPress(name) {
      this.cancelDatabaseLongPress()
      this.databaseLongPressHandled = false
      this.databaseLongPressTimer = setTimeout(() => this.renameDatabase(name), DATABASE_LONG_PRESS_MS)
    },
    cancelDatabaseLongPress() {
      clearTimeout(this.databaseLongPressTimer)
      this.databaseLongPressTimer = null
    },
    async renameDatabase(name) {
      this.cancelDatabaseLongPress()
      this.databaseLongPressHandled = true
      const newName = (prompt('重命名数据库', name) || '').trim()
      if (!newName || newName === name || this.databaseBusy) return
      if (this.databaseNames.some((existing) => existing !== name && existing.toLowerCase() === newName.toLowerCase())) {
        alert('已有同名数据库')
        return
      }
      this.databaseBusy = true
      try {
        this.adoptChangedDatabase(await NativeRapfi.renameDatabase({ name, newName }))
      } catch (error) {
        alert('重命名失败: ' + error.message)
      } finally {
        this.databaseBusy = false
      }
    },
    async setDatabaseMode(mode) {
      if (this.databaseBusy) return
      this.databaseBusy = true
      try {
        this.adoptChangedDatabase(await NativeRapfi.setDatabaseMode({ mode }))
      } catch (error) {
        this.$vux.toast.text('数据库设置失败')
      } finally {
        this.databaseBusy = false
      }
    },
    async switchDatabase(name) {
      if (this.databaseBusy || !name) return
      this.databaseBusy = true
      try {
        this.adoptChangedDatabase(await NativeRapfi.switchDatabase({ name }))
        this.databasePickerOpen = false
      } catch (error) {
        this.$vux.toast.text('数据库切换失败')
      } finally {
        this.databaseBusy = false
      }
    },
    openCreateDatabase() {
      this.databasePickerOpen = false
      this.newDatabaseName = this.databaseTimestampName()
      this.createDatabaseDialog = true
      this.$nextTick(() => {
        if (this.$refs.databaseNameInput) {
          this.$refs.databaseNameInput.focus()
          this.$refs.databaseNameInput.select()
        }
      })
    },
    databaseTimestampName() {
      const now = new Date()
      const pad = (value) => String(value).padStart(2, '0')
      const base = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
      let candidate = base
      let suffix = 2
      while (this.databaseNames.some((name) => name.toLowerCase() === candidate.toLowerCase())) {
        candidate = `${base}-${suffix++}`
      }
      return candidate
    },
    async createDatabase() {
      const name = (this.newDatabaseName || '').trim() || this.databaseTimestampName()
      if (this.databaseBusy) return
      if (this.databaseNames.some((existing) => existing.toLowerCase() === name.toLowerCase())) {
        this.$vux.toast.text('已有同名数据库')
        return
      }
      this.databaseBusy = true
      try {
        this.adoptChangedDatabase(await NativeRapfi.createDatabase({ name }))
        this.newDatabaseName = ''
        this.createDatabaseDialog = false
      } catch (error) {
        this.$vux.toast.text('数据库创建失败')
      } finally {
        this.databaseBusy = false
      }
    },
    async importDatabase() {
      if (this.databaseBusy) return
      this.databaseBusy = true
      try {
        this.adoptChangedDatabase(await NativeRapfi.importDatabase())
        this.databasePickerOpen = false
      } catch (error) {
        this.$vux.toast.text('数据库导入失败')
      } finally {
        this.databaseBusy = false
      }
    },
    async exportDatabase(name = this.databaseCurrent) {
      if (this.databaseBusy || !name) return
      this.databaseBusy = true
      try {
        await NativeRapfi.exportDatabase({ name })
      } catch (error) {
        this.$vux.toast.text('数据库导出失败')
      } finally {
        this.databaseBusy = false
      }
    },
    async deleteDatabase(name) {
      if (this.databaseBusy || !name || name === this.databaseCurrent) return
      if (!window.confirm(`删除数据库「${name}」？`)) return
      this.databaseBusy = true
      try {
        this.adoptDatabaseStatus(await NativeRapfi.deleteDatabase({ name }))
      } catch (error) {
        this.$vux.toast.text('数据库删除失败')
      } finally {
        this.databaseBusy = false
      }
    },
    reset() {
      this.clearCookies()
      location.reload()
    },
  },
  watch: {
    engineType(value) {
      if (value === 'RAPFI') this.refreshDatabaseStatus()
    },
  },
  // The page is kept alive, and analysis may keep adding records while it is shown.
  activated() {
    this.startDatabaseStatusPolling()
  },
  deactivated() {
    this.stopDatabaseStatusPolling()
  },
  beforeDestroy() {
    this.stopDatabaseStatusPolling()
    this.cancelDatabaseLongPress()
  },
}
</script>

<style lang="less" scoped>
.settings {
  max-width: 100%;

  @media (min-width: 800px) {
    max-width: 800px;
    margin: 0 auto; // Center align if necessary
  }
}

.database-picker {
  background: #f1f2f3;
  padding-bottom: env(safe-area-inset-bottom);
}
.engine-picker {
  background: #fff;
}
.engine-picker-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 64px;
  padding: 0 24px;
  border: 0;
  border-bottom: 1px solid #eee;
  background: #fff;
  text-align: left;
  font-size: 18px;
  color: #222;
}
.engine-picker-check {
  color: #1aad19;
  font-size: 22px;
}
.database-picker-row {
  display: flex;
  align-items: center;
  min-height: 48px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}
.database-picker-name {
  flex: 1;
  min-width: 0;
  align-self: stretch;
  border: 0;
  padding: 0;
  background: transparent;
  text-align: left;
  font-size: 16px;
  color: #333;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.database-action {
  flex: 0 0 48px;
  box-sizing: border-box;
  border: 0;
  background: #fff;
  color: #888;
  padding: 6px 0;
  text-align: center;
}
.database-delete {
  color: #e64340;
}
.database-picker-action-row {
  min-height: 48px;
  padding: 14px 16px;
  box-sizing: border-box;
  background: #fff;
  color: #333;
  font-size: 16px;
}
.database-picker-first-action {
  margin-top: 0.2em;
}
.database-check {
  padding: 0;
  color: #1aad19;
  font-size: 20px;
}
.database-create-dialog {
  padding: 18px;
  background: #fff;
}
.database-create-title {
  margin-bottom: 12px;
  font-size: 17px;
}
.database-create-dialog input {
  width: 100%;
  box-sizing: border-box;
  padding: 9px;
  border: 1px solid #ddd;
  border-radius: 3px;
  font-size: 16px;
}
.database-create-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 18px;
  margin-top: 16px;
}
.database-create-buttons button {
  border: 0;
  background: transparent;
  color: #888;
  font-size: 15px;
}
.database-create-buttons .database-confirm {
  color: #1aad19;
}
</style>
