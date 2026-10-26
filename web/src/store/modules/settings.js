import { version } from '@/../package.json'

export const FREESTYLE = 0,
  STANDARD = 1,
  RENJU = 2

export const CONFIGS = ['config.toml', 'classical220723.toml', 'classical210901.toml']
const SETTINGS_KEY = 'GMKC_CFG'
const versionedSettingsKey = `${SETTINGS_KEY}_${version}`

const state = {
  language: null,
  boardStyle: {
    boardColor: '#F4D03F',
    lineColor: '#000000',
    lineWidth: 0.03,
    coordColor: '#000000',
    coordFontStyle: '',
    coordFontFamily: 'sans-serif',
    starRadiusScale: 0.1,
    pieceBlack: '#000000',
    pieceWhite: '#FFFFFF',
    pieceStrokeWidth: 0.021,
    pieceStrokeBlack: '#000000',
    pieceStrokeWhite: '#000000',
    pieceScale: 0.95,
    indexColorBlack: '#FFFFFF',
    indexColorWhite: '#000000',
    indexFontStyle: 'bold',
    indexFontFamily: 'sans-serif',
    indexScale: 0.45,
    lastStepColor: '#E74C3C',
    lastStepScale: 0.15,
    winlineWidth: 0.12,
    winlineColor: '#2E86C1',
    bestMoveColor: '#E74C3C',
    thoughtMoveColor: '#3C5EE7',
    lostMoveColor: '#FDFEFE',
    bestMoveScale: 0.12,
    realtimeMoveScale: 0.09,
    selectionStrokeWidth: 0.08,
    selectionStrokeColor: '#E74C3C',
    forbidStrokeWidth: 0.12,
    forbidStrokeColor: '#E74C3C',
    pvEvalFontStyle: 600,
    pvEvalFontFamily: 'sans-serif',
    pvEvalScale: 0.45,
    pvEvalAlpha: 0.9,
  },
  boardSize: 15,
  thinkTimeOption: 1,
  turnTime: 5000,
  matchTime: 9999000,
  maxDepth: 64,
  maxNodes: 0,
  rule: 0, // 规则: 0-无禁手 1-无禁长连不赢 2,4-有禁手 5-无禁一手交换
  threads: null, // 兼容旧配置的线程数
  rapfiThreads: null, // Rapfi 原生线程数
  engineType: 'RAPFI', // 当前引擎：RAPFI / KATAGO
  strength: 100, // 棋力限制 (默认100%棋力)
  nbest: 1, // MultiPV多点分析
  kataGoMaxPoints: 10, // KataGo UI最多显示的候选点数（底层始终分析全盘）
  configIndex: 0, // 配置序号: [0, CONFIGS.length)
  candRange: 3, // 选点范围: {0, 1, 2, 3, 4, 5}
  hashSize: 128, // 置换表大小, 单位 MiB
  pondering: false, // 后台思考
  clickCheck: 0, // 点击方式: 0-直接落子 1-二次确认 2-滑动落子
  indexOrigin: 0, // 棋子序号起点
  showCoord: true,
  showAnalysis: true,
  showDetail: true,
  showPvEval: 0, // 是否显示实时估值: 0-不显示 1-显示估值 2-显示胜率
  showIndex: true,
  showLastStep: true,
  showWinline: true,
  showForbid: true,
  showHeatmap: true,
  showKillPoints: true,
  showVisitsPerSecond: true,
  aiThinkBlack: false,
  aiThinkWhite: false,
}

const propertiesToSave = [
  'language',
  'boardSize',
  'thinkTimeOption',
  'turnTime',
  'matchTime',
  'maxDepth',
  'maxNodes',
  'rule',
  'threads',
  'rapfiThreads',
  'strength',
  'nbest',
  'kataGoMaxPoints',
  'configIndex',
  'candRange',
  'hashSize',
  'pondering',
  'clickCheck',
  'showCoord',
  'showAnalysis',
  'showDetail',
  'showPvEval',
  'showIndex',
  'showLastStep',
  'showWinline',
  'showForbid',
  'showHeatmap',
  'showKillPoints',
  'showVisitsPerSecond',
  'aiThinkBlack',
  'aiThinkWhite',
]

const boardPropertiesToSave = [
  'boardColor',
  'lastStepColor',
  'winlineColor',
  'bestMoveColor',
  'thoughtMoveColor',
  'lostMoveColor',
]

const getters = {
  turnTime: (state) => {
    let turn = [state.turnTime, 7000, 40000, -1]
    return turn[state.thinkTimeOption]
  },
  matchTime: (state) => {
    let match = [state.matchTime, 180000, 900000, -1]
    return match[state.thinkTimeOption]
  },
  depth: (state) => {
    return state.thinkTimeOption == 0 ? state.maxDepth : 100
  },
  nodes: (state) => {
    return state.thinkTimeOption == 0 ? state.maxNodes : 0
  },
  gameRule: (state) => {
    switch (state.rule) {
      case 0:
      case 5:
        return FREESTYLE
      case 1:
        return STANDARD
      case 2:
      case 4:
        return RENJU
      default:
        throw Error('unknown rule')
    }
  },
}

function saveCookies() {
  let stateToSave = {}
  for (let p of propertiesToSave) stateToSave[p] = state[p]
  for (let p of boardPropertiesToSave) stateToSave[p] = state.boardStyle[p]
  const serialized = JSON.stringify(stateToSave)
  localStorage.setItem(versionedSettingsKey, serialized)
  localStorage.setItem(SETTINGS_KEY, serialized)
}

function applyValue(state, key, value) {
  if (key === 'engineType') {
    state.engineType = String(value).toUpperCase() === 'KATAGO' ? 'KATAGO' : 'RAPFI'
  } else if (state.engineType === 'KATAGO' && key === 'boardSize') {
    state.boardSize = 15
  } else if (state.engineType === 'KATAGO' && key === 'rule') {
    state.rule = 2
  } else if (state.engineType === 'KATAGO' && (key === 'aiThinkBlack' || key === 'aiThinkWhite')) {
    state[key] = false
  } else {
    state[key] = value
  }

  if (state.engineType === 'KATAGO') {
    state.boardSize = 15
    state.rule = 2
    state.aiThinkBlack = false
    state.aiThinkWhite = false
  }
}

const mutations = {
  setValue(state, payload) {
    applyValue(state, payload.key, payload.value)
    if (propertiesToSave.includes(payload.key)) saveCookies()
  },
  setValueNoSave(state, payload) {
    applyValue(state, payload.key, payload.value)
  },
  setBoardStyle(state, payload) {
    state.boardStyle[payload.key] = payload.value
    if (boardPropertiesToSave.includes(payload.key)) saveCookies()
  },
  setBoardStyleNoSave(state, payload) {
    state.boardStyle[payload.key] = payload.value
  },
}

const actions = {
  readCookies({ commit }) {
    let json
    try {
      json = localStorage.getItem(versionedSettingsKey) || localStorage.getItem(SETTINGS_KEY)
    } catch (error) {
      saveCookies()
      return
    }
    if (!json) {
      saveCookies()
      return
    }

    let stateToRead
    try {
      stateToRead = JSON.parse(json)
    } catch (error) {
      localStorage.removeItem(versionedSettingsKey)
      localStorage.removeItem(SETTINGS_KEY)
      saveCookies()
      return
    }
    if (typeof stateToRead.engineType === 'string') {
      stateToRead.engineType = stateToRead.engineType.toUpperCase() === 'KATAGO' ? 'KATAGO' : 'RAPFI'
    }
    if (stateToRead.rapfiThreads === undefined && stateToRead.threads !== undefined) {
      stateToRead.rapfiThreads = stateToRead.threads
    }
    // Keep defaults for fields introduced after an existing installation.
    for (let p of propertiesToSave) {
      if (Object.prototype.hasOwnProperty.call(stateToRead, p) && stateToRead[p] !== undefined) {
        commit('setValueNoSave', { key: p, value: stateToRead[p] })
      }
    }
    for (let p of boardPropertiesToSave) {
      if (Object.prototype.hasOwnProperty.call(stateToRead, p) && stateToRead[p] !== undefined) {
        commit('setBoardStyleNoSave', { key: p, value: stateToRead[p] })
      }
    }
    // Rewrite the normalized snapshot so newly introduced defaults and engine
    // constraints are retained on the next launch.
    saveCookies()
  },
  clearCookies() {
    localStorage.removeItem(versionedSettingsKey)
    localStorage.removeItem(SETTINGS_KEY)
  },
}

export default {
  namespaced: true,
  state,
  getters,
  actions,
  mutations,
}
