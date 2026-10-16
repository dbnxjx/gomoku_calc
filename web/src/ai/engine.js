import { registerPlugin } from '@capacitor/core'

// The web page owns the protocol parser. The actual engines are supplied by
// future Android Capacitor plugins; this module never loads browser WASM.
const NativeRapfi = registerPlugin('NativeRapfi')
const NativeKataGo = registerPlugin('NativeKataGo')
const plugins = {
  RAPFI: NativeRapfi,
  KATAGO: NativeKataGo,
}
let callback = () => {}
let initialized = false
let activeEngineType = 'RAPFI'
let activePlugin = NativeRapfi
const listenersAttached = {}

function emit(result) { callback(result) }

function parseStdout(output, analysisId) {
  if (typeof output !== 'string' || output.length === 0) return
  const emitResult = (result) => {
    if (analysisId === undefined || analysisId === null) return emit(result)
    return emit(Object.assign({ analysisId }, result))
  }
  let i = output.indexOf(' ')
  if (i === -1) {
    if (output === 'OK') return
    if (output === 'SWAP') return emitResult({ swap: true })
    const coord = output.split(',')
    if (coord.length === 2) return emitResult({ pos: [+coord[0], +coord[1]] })
    return emitResult({ unknown: output })
  }

  let head = output.substring(0, i)
  let tail = output.substring(i + 1)
  if (head === 'MESSAGE') {
    if (tail.startsWith('REALTIME')) {
      const realtime = tail.split(' ')
      const result = { type: realtime[1] }
      if (realtime[2]) result.pos = realtime[2].split(',').map(Number)
      return emitResult({ realtime: result })
    }
    return emitResult({ msg: tail })
  }
  if (head === 'INFO') {
    i = tail.indexOf(' ')
    if (i === -1) return emitResult({ unknown: tail })
    head = tail.substring(0, i)
    tail = tail.substring(i + 1)
    if (head === 'PV') return emitResult({ multipv: tail })
    if (head === 'NUMPV') return emitResult({ numpv: +tail })
    if (head === 'DEPTH') return emitResult({ depth: +tail })
    if (head === 'SELDEPTH') return emitResult({ seldepth: +tail })
    if (head === 'NODES') return emitResult({ nodes: +tail })
    if (head === 'TOTALNODES') return emitResult({ totalnodes: +tail })
    if (head === 'TOTALTIME') return emitResult({ totaltime: +tail })
    if (head === 'SPEED' || head === 'VISITS') return emitResult({ speed: +tail, visitsPerSecond: +tail })
    if (head === 'BLACK_WINRATE') return emitResult({ blackWinRate: parseFloat(tail) })
    if (head === 'EVAL') return emitResult({ eval: tail })
    if (head === 'WINRATE') return emitResult({ winrate: parseFloat(tail) })
    if (head === 'BESTLINE') {
      const points = tail.match(/\d+,\d+/g) || []
      return emitResult({ bestline: points.map((point) => point.split(',').map(Number)) })
    }
    if (head === 'RANK') return emitResult({ rank: +tail })
  }
  if (head === 'HEATMAP') {
    try {
      return emitResult({ heatmap: JSON.parse(tail) })
    } catch (e) {
      return emitResult({ unknown: output })
    }
  }
  if (head === 'FORBID') {
    return emitResult({ forbid: (tail.match(/.{4}/g) || []).map((point) => {
      const coord = point.match(/([0-9][0-9])([0-9][0-9])/)
      return coord ? [+coord[1], +coord[2]] : null
    }).filter(Boolean) })
  }
  if (head === 'ERROR') return emitResult({ error: tail })
  if (head.indexOf(',') !== -1) {
    const coord1 = head.split(',')
    const coord2 = tail.split(',')
    return emitResult({ pos: [+coord1[0], +coord1[1]], pos2: [+coord2[0], +coord2[1]] })
  }
  emitResult({ unknown: tail })
}

function readEvent(event) {
  if (typeof event === 'string') return event
  if (!event) return ''
  return event.data || event.output || event.value || ''
}

async function attachListeners(plugin, engineType) {
  if (listenersAttached[engineType] || !plugin.addListener) return
  listenersAttached[engineType] = true
  await plugin.addListener('stdout', (event) => {
    const analysisId = event && typeof event === 'object' ? event.analysisId : undefined
    parseStdout(readEvent(event), analysisId)
  })
  await plugin.addListener('stderr', (event) => emit({ error: readEvent(event), engineType }))
  await plugin.addListener('debug', (event) => emit({ debug: readEvent(event), engineType }))
  await plugin.addListener('status', (event) => emit({ msg: readEvent(event), engineType }))
  await plugin.addListener('benchmark', (event) => {
    const data = event && typeof event === 'object' ? event : {}
    emit({ benchmark: data.message || '', benchmarking: !!data.running, engineType })
  })
  await plugin.addListener('exit', (event) => {
    initialized = false
    emit({ msg: 'Native ' + engineType + ' exited: ' + readEvent(event), engineType })
  })
}

async function init(callbackFn_, loadFullEngine, engineType = 'RAPFI') {
  callback = typeof callbackFn_ === 'function' ? callbackFn_ : () => {}
  activeEngineType = String(engineType).toUpperCase() === 'KATAGO' ? 'KATAGO' : 'RAPFI'
  activePlugin = plugins[activeEngineType]
  initialized = false
  try {
    await attachListeners(activePlugin, activeEngineType)
    await activePlugin.initialize({ engineType: activeEngineType })
    initialized = true
    emit({ ok: true, engineType: activeEngineType })
  } catch (error) {
    emit({
      error: 'Native ' + activeEngineType + ' engine is not connected: ' + (error.message || error),
      engineType: activeEngineType,
    })
  }
  return 'native://' + activeEngineType.toLowerCase()
}

function sendCommand(command) {
  if (typeof command !== 'string' || command.length === 0 || !initialized) return
  Promise.resolve(activePlugin.sendCommand({ command })).catch((error) => {
    emit({ error: 'Native ' + activeEngineType + ' command failed: ' + (error.message || error), engineType: activeEngineType })
  })
}

function stopThinking() {
  if (!initialized) return false
  Promise.resolve(activePlugin.stop()).catch((error) => {
    emit({ error: 'Native ' + activeEngineType + ' stop failed: ' + (error.message || error), engineType: activeEngineType })
  })
  return true
}

function runBenchmark() {
  if (!initialized || activeEngineType !== 'KATAGO') {
    return Promise.reject(new Error('KataGo engine is not ready'))
  }
  return activePlugin.benchmark()
}

export { init, sendCommand, stopThinking, runBenchmark }
