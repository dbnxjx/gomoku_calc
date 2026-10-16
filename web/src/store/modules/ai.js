import * as engine from '@/ai/engine'
import { RENJU, CONFIGS } from './settings'

const state = {
  fullEngine: false,
  engineBackend: 'native-rapfi',
  nativeEngineAvailable: false,
  nativeRapfiAvailable: false,
  nativeKataGoAvailable: false,
  loadingProgress: 0.0,
  ready: false,
  startSize: 0,
  restart: false,
  thinking: false,
  analysisGeneration: 0,
  kataGoPaused: false,
  kataGoBenchmarking: false,
  timeUsed: 0,
  lastThinkTime: 0,
  lastThinkPosition: [],
  lastHeatmapLogAt: 0,
  currentConfig: null,
  hashSize: null,
  outputs: {
    pos: null,
    swap: null,
    currentPV: 0,
    pv: [
      {
        depth: 0,
        seldepth: 0,
        nodes: 0,
        eval: '-',
        winrate: 0.0,
        bestline: [],
      },
    ],
    nodes: 0,
    speed: 0,
    visitsPerSecond: 0,
    blackWinRate: null,
    heatmap: [],
    killPoints: [],
    time: 0,
    msg: null,
    realtime: {
      best: [],
      lost: [],
    },
    forbid: [],
    error: null,
  },
  messages: [],
  posCallback: null,
}

const getters = {
  bestlineStr: (state) => (pvIdx) => {
    if (!pvIdx) pvIdx = 0
    let posStrs = []
    for (let p of state.outputs.pv[pvIdx].bestline) {
      const coordX = String.fromCharCode('A'.charCodeAt(0) + p[0])
      const coordY = (state.startSize - p[1]).toString()
      posStrs.push(coordX + coordY)
    }
    return posStrs.join(' ')
  },
}

const mutations = {
  setFullEngine(state, fullEngine) {
    state.fullEngine = fullEngine
  },
  setNativeEngineAvailable(state, available) {
    state.nativeEngineAvailable = available
  },
  setNativeAvailability(state, { engineType, available }) {
    if (engineType === 'KATAGO') {
      state.nativeKataGoAvailable = available
    } else {
      state.nativeRapfiAvailable = available
      state.nativeEngineAvailable = available
    }
  },
  setLoadingProgress(state, progress) {
    state.loadingProgress = progress
  },
  setReady(state, ready) {
    state.ready = ready
  },
  setThinkingState(state, thinking) {
    state.thinking = thinking
  },
  advanceAnalysisGeneration(state) {
    state.analysisGeneration += 1
  },
  setKataGoPaused(state, paused) {
    state.kataGoPaused = paused
  },
  setKataGoBenchmarking(state, running) {
    state.kataGoBenchmarking = !!running
  },
  setStartSize(state, size) {
    state.startSize = size
  },
  setRestart(state, enabled = true) {
    state.restart = enabled
  },
  clearUsedTime(state) {
    state.timeUsed = 0
  },
  addUsedTime(state) {
    state.timeUsed += Date.now() - state.lastThinkTime
  },
  setThinkStartTime(state) {
    state.lastThinkTime = Date.now()
  },
  setCurrentConfig(state, config) {
    state.currentConfig = config
  },
  setHashSize(state, hashSize) {
    state.hashSize = hashSize
  },
  addMessage(state, msg) {
    state.messages.push(msg)
    if (state.messages.length > 300) state.messages.splice(0, state.messages.length - 300)
  },
  clearMessage(state) {
    state.messages = []
  },
  setOutput(state, output) {
    state.outputs[output.key] = output.value
  },
  setCurrentPV(state, pvIdx) {
    state.outputs.currentPV = pvIdx
  },
  setPVOutput(state, output) {
    let pv = state.outputs.pv[state.outputs.currentPV]
    if (!pv) {
      state.outputs.pv[state.outputs.currentPV] = {}
      pv = state.outputs.pv[state.outputs.currentPV]
    }
    pv[output.key] = output.value
  },
  clearOutput(state) {
    state.outputs.pv = [
      {
        depth: 0,
        seldepth: 0,
        nodes: 0,
        eval: '-',
        winrate: 0.0,
        bestline: [],
      },
    ]
    state.outputs.pos = null
    state.outputs.nodes = 0
    state.outputs.speed = 0
    state.outputs.visitsPerSecond = 0
    state.outputs.blackWinRate = null
    state.outputs.heatmap = []
    state.outputs.killPoints = []
    state.outputs.forbid = []
  },
  addRealtime(state, rt) {
    state.outputs.realtime[rt.type].push(rt.pos)
  },
  clearRealtime(state, type) {
    state.outputs.realtime[type] = []
  },
  setPosCallback(state, callback) {
    state.posCallback = callback
  },
  sortPV(state) {
    let isPosEqual = (m) =>
      state.outputs.pos ? m[0] == state.outputs.pos[0] && m[1] == state.outputs.pos[1] : false
    let evalStrToEval = (e) => {
      let val = +e
      if (isNaN(val)) {
        if (e.startsWith('+M')) val = 40000 - +e.substring(2)
        else if (e.startsWith('-M')) val = -40000 + +e.substring(2)
        else val = -80000
      }
      return val
    }

    state.outputs.pv.sort((a, b) => {
      if (isPosEqual(a.bestline[0])) return -1
      else if (isPosEqual(b.bestline[0])) return 1
      return evalStrToEval(b.eval) - evalStrToEval(a.eval)
    })
  },
  setLastThinkPosition(state, position) {
    state.lastThinkPosition = [...position]
  },
  setLastHeatmapLogAt(state, time) {
    state.lastHeatmapLogAt = time
  },
}

const actions = {
  async initEngine({ commit, dispatch, state, rootState }, loadFullEngine) {
    commit('setLoadingProgress', 0.0)
    commit('setReady', false)
    commit('setKataGoPaused', false)
    commit(
      'addMessage',
      `WebView: secure=${window.isSecureContext ? 1 : 0}, isolated=${window.crossOriginIsolated ? 1 : 0}, SAB=${typeof SharedArrayBuffer !== 'undefined' ? 1 : 0}`
    )
    const selectedEngineType = String(rootState.settings.engineType || 'rapfi').toUpperCase() === 'KATAGO' ? 'KATAGO' : 'RAPFI'
    commit('setOutput', { key: 'forbid', value: [] })
    commit('setNativeAvailability', { engineType: selectedEngineType, available: false })
    if (selectedEngineType === 'RAPFI') commit('setNativeEngineAvailable', false)
    const callback = (r) => {
      const eventEngineType = r.engineType || selectedEngineType
      if (
        eventEngineType === 'KATAGO' &&
        r.analysisId !== undefined &&
        Number(r.analysisId) !== state.analysisGeneration
      ) return
      if (r.benchmark !== undefined) {
        commit('setKataGoBenchmarking', r.benchmarking)
        if (r.benchmark) commit('addMessage', '[KATAGO] ' + r.benchmark)
      } else if (r.debug) {
        commit('addMessage', '[' + eventEngineType + '] ' + r.debug)
      } else if (r.heatmap) {
        const previousCount = state.outputs.heatmap.length
        commit('setOutput', { key: 'heatmap', value: r.heatmap })
        if (eventEngineType === 'KATAGO') {
          const totalVisits = r.heatmap.reduce((total, point) => total + (Number(point[3]) || 0), 0)
          commit('setOutput', { key: 'nodes', value: totalVisits })
          const now = Date.now()
          if (r.heatmap.length !== previousCount || now - state.lastHeatmapLogAt >= 1000) {
            const maxRank = r.heatmap.reduce((rank, point) => Math.max(rank, Number(point[4]) || 0), 0)
            commit('setLastHeatmapLogAt', now)
            commit('addMessage', `KataGo heatmap candidates=${r.heatmap.length}, maxRank=${maxRank}, totalVisits=${totalVisits}`)
          }
        }
      } else if (r.killPoints) {
        commit('setOutput', { key: 'killPoints', value: r.killPoints })
      } else if (r.realtime) {
        switch (r.realtime.type) {
          case 'LOST':
            commit('addRealtime', { type: 'lost', pos: r.realtime.pos })
            break
          case 'BEST':
            commit('clearRealtime', 'best')
            commit('addRealtime', { type: 'best', pos: r.realtime.pos })
            break
          default:
            break
        }
      } else if (r.msg) {
        commit('setOutput', { key: 'msg', value: r.msg })
        commit('addMessage', r.msg)
      } else if (r.multipv) {
        if (r.multipv == 'DONE') {
          commit('setCurrentPV', 0)
        } else {
          commit('setCurrentPV', +r.multipv)
        }
      } else if (r.depth !== undefined) {
        commit('setPVOutput', { key: 'depth', value: r.depth })
      } else if (r.seldepth !== undefined) {
        commit('setPVOutput', { key: 'seldepth', value: r.seldepth })
      } else if (r.nodes !== undefined) {
        commit('setPVOutput', { key: 'nodes', value: r.nodes })
      } else if (r.totalnodes !== undefined) {
        if (eventEngineType !== 'KATAGO') commit('setOutput', { key: 'nodes', value: r.totalnodes })
      } else if (r.totaltime !== undefined) {
        commit('setOutput', { key: 'time', value: r.totaltime })
      } else if (r.speed !== undefined) {
        commit('setOutput', { key: 'speed', value: r.speed })
        commit('setOutput', { key: 'visitsPerSecond', value: r.visitsPerSecond || r.speed })
      } else if (r.visitsPerSecond !== undefined) {
        commit('setOutput', { key: 'visitsPerSecond', value: r.visitsPerSecond })
      } else if (r.blackWinRate !== undefined) {
        commit('setOutput', { key: 'blackWinRate', value: r.blackWinRate })
      } else if (r.eval) {
        commit('setPVOutput', { key: 'eval', value: r.eval })
      } else if (r.winrate !== undefined) {
        commit('setPVOutput', { key: 'winrate', value: r.winrate })
      } else if (r.bestline) {
        commit('setPVOutput', { key: 'bestline', value: r.bestline })
      } else if (r.pos) {
        commit('setOutput', { key: 'pos', value: r.pos })
        commit('addUsedTime')
        commit('clearRealtime', 'best')
        commit('clearRealtime', 'lost')
        commit('sortPV')
        commit('setThinkingState', false)

        if (state.posCallback) state.posCallback(r.pos)
      } else if (r.swap) {
        commit('setOutput', { key: 'swap', value: r.swap })
      } else if (r.forbid) {
        commit('setOutput', { key: 'forbid', value: r.forbid })
      } else if (r.error) {
        if (eventEngineType === 'RAPFI') commit('setNativeEngineAvailable', false)
        commit('setNativeAvailability', { engineType: eventEngineType, available: false })
        commit('setOutput', { key: 'error', value: r.error })
        commit('addMessage', 'Error: ' + r.error)
      } else if (r.ok) {
        if (eventEngineType === 'RAPFI') commit('setNativeEngineAvailable', true)
        commit('setNativeAvailability', { engineType: eventEngineType, available: true })
        commit('addMessage', 'Engine ready.')
        commit('setReady', true)
        dispatch('checkForbid')
      } else if (r.loading) {
        commit('setLoadingProgress', r.loading.progress)
      }
    }
    const engineURL = await engine.init(callback, loadFullEngine, selectedEngineType)
    commit('setFullEngine', false)
    commit('addMessage', 'Selected engine: ' + selectedEngineType)
    commit('addMessage', 'Engine backend: ' + engineURL)
  },
  sendInfo({ rootState, rootGetters }) {
    const engineType = String(rootState.settings.engineType || 'rapfi').toUpperCase() === 'KATAGO' ? 'KATAGO' : 'RAPFI'
    if (engineType === 'KATAGO') return
    engine.sendCommand('INFO RULE ' + rootState.settings.rule)
    engine.sendCommand('INFO THREAD_NUM ' + (rootState.settings.rapfiThreads || rootState.settings.threads || 1))
    engine.sendCommand('INFO CAUTION_FACTOR ' + rootState.settings.candRange)
    engine.sendCommand('INFO STRENGTH ' + rootState.settings.strength)
    engine.sendCommand('INFO TIMEOUT_TURN ' + rootGetters['settings/turnTime'])
    engine.sendCommand('INFO TIMEOUT_MATCH ' + rootGetters['settings/matchTime'])
    engine.sendCommand('INFO MAX_DEPTH ' + rootGetters['settings/depth'])
    engine.sendCommand('INFO MAX_NODE ' + rootGetters['settings/nodes'])
    engine.sendCommand('INFO SHOW_DETAIL ' + (rootState.settings.showDetail ? 3 : 2))
    engine.sendCommand('INFO PONDERING ' + (rootState.settings.pondering ? 1 : 0))
    engine.sendCommand('INFO SWAPABLE ' + (rootState.position.swaped ? 0 : 1))
  },
  sendBoard({ rootState }, immediateThink) {
    let position = rootState.position.position

    let command = immediateThink ? 'BOARD' : 'YXBOARD'
    // The position array is always stored in move order, starting with Black.
    // An odd number of stones means White is next; it must not change the
    // color of the first stone in the submitted position.
    let side = 1
    for (let pos of position) {
      command += ' ' + pos[0] + ',' + pos[1] + ',' + side
      side = 3 - side
    }
    command += ' DONE'
    engine.sendCommand(command)
  },
  think({ commit, dispatch, state, rootState, rootGetters }, args) {
    if (!state.ready) {
      commit('addMessage', 'Engine is not ready!')
      return Promise.reject(new Error('Engine is not ready!'))
    }
    commit('advanceAnalysisGeneration')
    const analysisId = state.analysisGeneration
    commit('setThinkingState', true)
    commit('setKataGoPaused', false)
    commit('setOutput', { key: 'swap', value: false })
    commit('clearMessage')

    const engineType = String(rootState.settings.engineType || 'rapfi').toUpperCase()
    if (engineType !== 'KATAGO') {
      dispatch('reloadConfig')
      dispatch('updateHashSize')
      dispatch('sendInfo')
    }

    if (state.restart || state.startSize != rootState.position.size) {
      engine.sendCommand('START ' + rootState.position.size)
      commit('setRestart', false)
      commit('setStartSize', rootState.position.size)
      commit('clearUsedTime')
    }

    let timeLeft = Math.max(rootGetters['settings/matchTime'] - state.timeUsed, 1)
    if (engineType !== 'KATAGO') engine.sendCommand('INFO TIME_LEFT ' + timeLeft)

    commit('clearOutput')
    dispatch('sendBoard', false)
    commit('setThinkStartTime')
    commit('setLastThinkPosition', rootState.position.position)

    if (engineType === 'KATAGO') {
      engine.sendCommand('YXNBEST ' + Math.max(1, rootState.settings.kataGoMaxPoints || 10) + ' ' + analysisId)
      return Promise.resolve(null)
    } else if (args && args.balanceMode) {
      engine.sendCommand(
        (args.balanceMode == 2 ? 'YXBALANCETWO ' : 'YXBALANCEONE ') + (args.balanceBias || 0)
      )
    } else {
      engine.sendCommand('YXNBEST ' + rootState.settings.nbest)
    }

    return new Promise((resolve) => {
      commit('setPosCallback', resolve)
    })
  },
  stop({ commit, state, rootState }, options = {}) {
    if (!state.thinking) return

    const engineType = String(rootState.settings.engineType || 'rapfi').toUpperCase()
    const manual = options.manual !== false
    if (engineType === 'KATAGO') {
      commit('advanceAnalysisGeneration')
      engine.stopThinking()
      commit('setKataGoPaused', manual)
      commit('setThinkingState', false)
      commit('clearOutput')
      return true
    }

    if (engine.stopThinking()) {
      commit('setReady', false)
      commit('clearRealtime', 'best')
      commit('clearRealtime', 'lost')
      commit('addUsedTime')
      commit('sortPV')
      commit('setThinkingState', false)
      let pos = state.outputs.pv[0].bestline[0]
      if (pos) {
        commit('setOutput', { key: 'pos', value: pos })

        if (state.posCallback) state.posCallback(pos)
      }
      commit('setRestart')
      // Reset to initial state
      commit('setCurrentConfig', null)
      commit('setHashSize', null)
      return true
    }

    return false
  },
  async runKataGoBenchmark({ commit, dispatch, state, rootState }) {
    if (String(rootState.settings.engineType || 'rapfi').toUpperCase() !== 'KATAGO') {
      throw new Error('KataGo is not the selected engine')
    }
    if (state.kataGoBenchmarking) return
    commit('setKataGoBenchmarking', true)
    if (state.thinking) await dispatch('stop', { manual: true })
    try {
      await engine.runBenchmark()
    } catch (error) {
      commit('setKataGoBenchmarking', false)
      commit('addMessage', 'KataGo 自动测速启动失败：' + (error.message || error))
    }
  },
  restart({ commit }) {
    commit('advanceAnalysisGeneration')
    commit('setRestart')
    commit('clearUsedTime')
    commit('clearOutput')
  },
  invalidateAnalysis({ commit }) {
    commit('advanceAnalysisGeneration')
    commit('clearOutput')
  },
  reloadConfig({ commit, state, rootState }) {
    if (String(rootState.settings.engineType || 'rapfi').toUpperCase() === 'KATAGO') return
    if (rootState.settings.configIndex == state.currentConfig) return
    commit('setCurrentConfig', rootState.settings.configIndex)

    engine.sendCommand('RELOADCONFIG ' + (CONFIGS[state.currentConfig] || ''))
  },
  updateHashSize({ commit, state, rootState }) {
    if (String(rootState.settings.engineType || 'rapfi').toUpperCase() === 'KATAGO') return
    if (rootState.settings.hashSize == state.hashSize) return
    commit('setHashSize', rootState.settings.hashSize)

    engine.sendCommand('INFO HASH_SIZE ' + state.hashSize * 1024)
    commit('addMessage', `Hash size reset to ${state.hashSize} MB.`)
  },
  checkForbid({ commit, state, dispatch, rootState, rootGetters }) {
    commit('setOutput', { key: 'forbid', value: [] })
    if (!state.ready) return

    if (rootGetters['settings/gameRule'] == RENJU && String(rootState.settings.engineType || 'rapfi').toUpperCase() !== 'KATAGO') {
      dispatch('sendInfo')
      if (state.restart || state.startSize != rootState.position.size) {
        engine.sendCommand('START ' + rootState.position.size)
        commit('setRestart', false)
        commit('setStartSize', rootState.position.size)
      }
      dispatch('sendBoard', false)
      engine.sendCommand('YXSHOWFORBID')
    }
  },
}

export default {
  namespaced: true,
  state,
  getters,
  actions,
  mutations,
}
