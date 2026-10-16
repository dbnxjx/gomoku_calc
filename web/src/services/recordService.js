// 棋谱服务。文件格式使用 SGF，棋谱只保存真实落子和分支，不保存分析结果。
import { Capacitor } from '@capacitor/core'
import { Filesystem } from '@capacitor/filesystem'

const FILE_VERSION = '1.0'
const FILE_EXTENSION = '.sgf'
const RECORD_DIR = 'records'
const WEB_STORAGE_KEY = 'GMKC_RECORDS'
const DATA_DIRECTORY = 'DATA'
const UTF8_ENCODING = 'utf8'

function createMetadata(boardSize, rule) {
  return { version: FILE_VERSION, boardSize: boardSize || 15, rule: rule || 'standard', createdAt: new Date().toISOString(), lastModified: new Date().toISOString(), title: '', blackPlayer: '', whitePlayer: '' }
}

function formatDateName(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

function sanitizeName(name) {
  const fallback = formatDateName()
  return (name || fallback).trim().replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').slice(0, 80) || fallback
}

function filenameFromName(name) {
  return sanitizeName(name).replace(/\.(?:sgf|json)$/i, '') + FILE_EXTENSION
}

function pathFor(filename) { return `${RECORD_DIR}/${filename}` }

function cleanTreeForSgf(node) {
  const result = { x: Number(node && node.x) || -1, y: Number(node && node.y) || -1, color: node && node.color || null, children: [] }
  result.children = (node && node.children || []).filter((child) => child && !child.isCalculated && child.x >= 0 && child.y >= 0).map(cleanTreeForSgf)
  return result
}

function sgfNode(node) {
  const color = String(node.color).toLowerCase() === 'white' ? 'W' : 'B'
  const current = `;${color}[${String.fromCharCode(97 + Number(node.x))}${String.fromCharCode(97 + Number(node.y))}]`
  const children = node.children || []
  if (children.length === 0) return current
  if (children.length === 1) return current + sgfNode(children[0])
  return current + children.map((child) => `(${sgfNode(child)})`).join('')
}

function exportToSGF(treeData, metadata = {}) {
  const root = cleanTreeForSgf(treeData)
  const size = Number(metadata.boardSize) || 15
  const rule = String(metadata.rule || 'standard').replace(/[\x5B\x5D\x5C]/g, '')
  const children = root.children || []
  const body = children.length === 1 ? sgfNode(children[0]) : children.map((child) => `(${sgfNode(child)})`).join('')
  return `(;GM[1]FF[4]CA[UTF-8]SZ[${size}]RU[${rule}]${body})`
}

function importFromSGF(text) {
  const source = String(text || '')
  const root = { id: 0, x: -1, y: -1, color: null, depth: 0, children: [] }
  const cursor = { index: 0 }
  let nextId = 1
  let boardSize = 15
  let rule = 'standard'
  const skipSpace = () => { while (/\s/.test(source[cursor.index] || '')) cursor.index += 1 }
  const properties = () => {
    const result = {}
    while (cursor.index < source.length) {
      skipSpace()
      if (!/[A-Za-z]/.test(source[cursor.index] || '')) break
      let key = ''
      while (/[A-Za-z]/.test(source[cursor.index] || '')) key += source[cursor.index++]
      const values = []
      while (cursor.index < source.length) {
        skipSpace()
        if (source[cursor.index] !== '[') break
        cursor.index += 1
        let value = ''
        while (cursor.index < source.length) {
          const ch = source[cursor.index++]
          if (ch === '\\' && cursor.index < source.length) value += source[cursor.index++]
          else if (ch === ']') break
          else value += ch
        }
        values.push(value)
      }
      result[key] = values.length > 1 ? values : values[0] || ''
    }
    return result
  }
  function sequence(parent) {
    skipSpace()
    if (source[cursor.index] !== '(') return
    cursor.index += 1
    let current = parent
    while (cursor.index < source.length) {
      skipSpace()
      if (source[cursor.index] !== ';') break
      cursor.index += 1
      const props = properties()
      if (props.SZ) boardSize = parseInt(String(props.SZ).split(':')[0], 10) || boardSize
      if (props.RU) rule = String(props.RU)
      const move = props.B || props.W
      if (typeof move === 'string' && move.length === 2) {
        const x = move.charCodeAt(0) - 97
        const y = move.charCodeAt(1) - 97
        if (x >= 0 && y >= 0 && x < boardSize && y < boardSize) {
          const child = { id: nextId++, x, y, color: props.B ? 'black' : 'white', depth: current.depth + 1, children: [] }
          current.children.push(child)
          current = child
        }
      }
    }
    while (cursor.index < source.length) { skipSpace(); if (source[cursor.index] !== '(') break; sequence(current) }
    skipSpace()
    if (source[cursor.index] === ')') cursor.index += 1
  }
  sequence(root)
  return { metadata: { boardSize, rule }, tree: root }
}

function exportToJSON(treeData, metadata) { return { version: FILE_VERSION, metadata, tree: cleanTreeForSgf(treeData) } }
function importFromJSON(jsonString) {
  const data = JSON.parse(jsonString)
  if (!data.version || !data.tree) throw new Error('无效的旧棋谱文件格式')
  return { metadata: data.metadata || {}, tree: data.tree }
}

async function ensureRecordDir() {
  if (!Capacitor.isNativePlatform()) return
  try { await Filesystem.mkdir({ path: RECORD_DIR, directory: DATA_DIRECTORY, recursive: true }) } catch (e) { if (!/exist|already|overwrite/i.test(String(e.message || ''))) throw e }
}
function readWebRecords() { try { return JSON.parse(localStorage.getItem(WEB_STORAGE_KEY) || '{}') } catch (e) { return {} } }
function writeWebRecords(records) { localStorage.setItem(WEB_STORAGE_KEY, JSON.stringify(records)) }

async function listRecords() {
  if (!Capacitor.isNativePlatform()) {
    const records = readWebRecords()
    return Object.keys(records).filter((filename) => /\.(?:sgf|json)$/i.test(filename)).map((filename) => ({ filename, title: filename.replace(/\.(?:sgf|json)$/i, ''), mtime: records[filename].mtime || 0 })).sort((a, b) => b.mtime - a.mtime)
  }
  await ensureRecordDir()
  const result = await Filesystem.readdir({ path: RECORD_DIR, directory: DATA_DIRECTORY })
  const files = (result.files || []).map((file) => (typeof file === 'string' ? file : file.name)).filter((name) => name && /\.(?:sgf|json)$/i.test(name))
  const records = []
  for (const filename of files) {
    let mtime = 0
    try { mtime = (await Filesystem.stat({ path: pathFor(filename), directory: DATA_DIRECTORY })).mtime || 0 } catch (e) { /* ignore */ }
    records.push({ filename, title: filename.replace(/\.(?:sgf|json)$/i, ''), mtime })
  }
  return records.sort((a, b) => b.mtime - a.mtime)
}

async function saveRecord(filename, treeData, metadata = {}) {
  const safeFilename = filenameFromName(filename)
  const title = safeFilename.replace(/\.sgf$/i, '')
  const sgf = exportToSGF(treeData, { ...metadata, title })
  if (!Capacitor.isNativePlatform()) {
    const records = readWebRecords()
    records[safeFilename] = { metadata: { ...metadata, title }, sgf, mtime: Date.now() }
    writeWebRecords(records)
    return { filename: safeFilename, title, mtime: records[safeFilename].mtime }
  }
  await ensureRecordDir()
  await Filesystem.writeFile({ path: pathFor(safeFilename), directory: DATA_DIRECTORY, data: sgf, encoding: UTF8_ENCODING, recursive: true })
  const saved = await Filesystem.readFile({ path: pathFor(safeFilename), directory: DATA_DIRECTORY, encoding: UTF8_ENCODING })
  if (!saved || !saved.data) throw new Error('棋谱写入后无法读回')
  const stat = await Filesystem.stat({ path: pathFor(safeFilename), directory: DATA_DIRECTORY })
  return { filename: safeFilename, title, mtime: stat.mtime || Date.now() }
}

async function loadRecord(filename) {
  const safeFilename = /\.json$/i.test(filename) ? filename : filenameFromName(filename)
  if (!Capacitor.isNativePlatform()) {
    const record = readWebRecords()[safeFilename]
    if (!record) return null
    return record.sgf ? importFromSGF(record.sgf) : { metadata: record.metadata || {}, tree: record.tree }
  }
  const result = await Filesystem.readFile({ path: pathFor(safeFilename), directory: DATA_DIRECTORY, encoding: UTF8_ENCODING })
  return /\.json$/i.test(safeFilename) ? importFromJSON(result.data) : importFromSGF(result.data)
}

async function renameRecord(oldFilename, newName) {
  const oldSafe = oldFilename
  const newSafe = filenameFromName(newName)
  if (oldSafe === newSafe) return { filename: oldSafe, title: oldSafe.replace(/\.(?:sgf|json)$/i, '') }
  if (!Capacitor.isNativePlatform()) {
    const records = readWebRecords()
    if (!records[oldSafe]) throw new Error('棋谱不存在')
    records[newSafe] = records[oldSafe]
    records[newSafe].metadata = { ...records[newSafe].metadata, title: newSafe.replace(/\.sgf$/i, '') }
    records[newSafe].mtime = Date.now()
    delete records[oldSafe]
    writeWebRecords(records)
    return { filename: newSafe, title: newSafe.replace(/\.sgf$/i, ''), mtime: records[newSafe].mtime }
  }
  await ensureRecordDir()
  await Filesystem.rename({ from: pathFor(oldSafe), to: pathFor(newSafe), directory: DATA_DIRECTORY, toDirectory: DATA_DIRECTORY })
  return { filename: newSafe, title: newSafe.replace(/\.sgf$/i, ''), mtime: Date.now() }
}

async function deleteRecord(filename) {
  if (!Capacitor.isNativePlatform()) { const records = readWebRecords(); delete records[filename]; writeWebRecords(records); return }
  await ensureRecordDir()
  await Filesystem.deleteFile({ path: pathFor(filename), directory: DATA_DIRECTORY })
}

function parseEval(evalStr) {
  if (!evalStr || evalStr === '-') return { type: 'none', value: null }
  if (evalStr.startsWith('+M')) return { type: 'win', value: parseInt(evalStr.slice(2)) }
  if (evalStr.startsWith('-M')) return { type: 'loss', value: parseInt(evalStr.slice(2)) }
  return { type: 'score', value: parseInt(evalStr) || 0 }
}

export default { FILE_VERSION, FILE_EXTENSION, createMetadata, exportToJSON, importFromJSON, exportToSGF, importFromSGF, formatDateName, sanitizeName, filenameFromName, listRecords, saveRecord, loadRecord, renameRecord, deleteRecord, parseEval }
