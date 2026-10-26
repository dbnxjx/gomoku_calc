const NODE_SIZE = 16
const GAP_Y = 36
const PADDING = 20
const LABEL_WIDTH = 28
const BRANCH_GAP = 8

let idCounter = 0

class MoveNode {
  constructor(parent, x, y, color) {
    this.id = ++idCounter
    this.x = x
    this.y = y
    this.color = color
    this.depth = parent ? parent.depth + 1 : 0
    this.parent = parent
    this.children = []
    this.col = 0
    this.subtreeWidth = 0
    this.renderX = 0
    this.renderY = 0
  }

  get primaryChild() {
    return this.children.length > 0 ? this.children[0] : null
  }

  makeMove(x, y, color) {
    const existing = this.children.find(c => c.x === x && c.y === y)
    if (existing) return existing

    const newNode = new MoveNode(this, x, y, color)
    // Keep the original continuation first. New alternatives are appended so
    // the tree grows to the right without changing the forward button's path.
    this.children.push(newNode)
    return newNode
  }

  calcSubtreeWidth() {
    if (this.children.length === 0) {
      this.subtreeWidth = NODE_SIZE
      return this.subtreeWidth
    }

    let totalWidth = 0
    for (let i = 0; i < this.children.length; i++) {
      totalWidth += this.children[i].calcSubtreeWidth()
      if (i < this.children.length - 1) {
        totalWidth += BRANCH_GAP
      }
    }
    this.subtreeWidth = Math.max(NODE_SIZE, totalWidth)
    return this.subtreeWidth
  }

  assignPositions(x, y) {
    this.renderX = x + this.subtreeWidth / 2
    this.renderY = y + NODE_SIZE / 2

    if (this.children.length === 0) return

    let childX = x
    for (const child of this.children) {
      child.assignPositions(childX, y + GAP_Y)
      childX += child.subtreeWidth + BRANCH_GAP
    }
  }

  getMaxDepth() {
    if (this.children.length === 0) return this.depth
    return Math.max(...this.children.map(c => c.getMaxDepth()))
  }

  isOnCurrentPath(node) {
    let cur = node
    while (cur) {
      if (cur.id === this.id) return true
      cur = cur.parent
    }
    return false
  }

  // 棋谱树只包含实际落子和分支。
  toJSON() {
    return {
      x: this.x,
      y: this.y,
      color: this.color,
      children: this.children.map((child) => child.toJSON()),
    }
  }

  // 兼容旧棋谱，并丢弃旧格式中的计算候选节点和分析字段。
  static fromJSON(data, parent = null) {
    const node = new MoveNode(parent, data.x, data.y, data.color)
    if (data.children) {
      for (const childData of data.children) {
        if (!childData || childData.isCalculated || childData.x < 0 || childData.y < 0) continue
        const child = MoveNode.fromJSON(childData, node)
        node.children.push(child)
      }
    }
    return node
  }
}

const state = {
  rootNode: null,
  currentNodeId: null,
  version: 0,
  allNodes: {},
}

const getters = {
  rootNode: (s) => s.rootNode,
  currentNode: (s) => {
    if (!s.rootNode || !s.currentNodeId) return s.rootNode
    return findNodeById(s.rootNode, s.currentNodeId) || s.rootNode
  },
  version: (s) => s.version,
  getTreeJSON: (s) => {
    if (!s.rootNode) return null
    return s.rootNode.toJSON()
  },
  getPathToCurrent: (s) => {
    const path = []
    let node = findNodeById(s.rootNode, s.currentNodeId)
    while (node) {
      if (node.x >= 0 && node.y >= 0) path.unshift([node.x, node.y])
      node = node.parent
    }
    return path
  },
}

function findNodeById(node, id) {
  if (!node) return null
  if (node.id === id) return node
  for (const child of node.children) {
    const found = findNodeById(child, id)
    if (found) return found
  }
  return null
}

const mutations = {
  init(s) {
    idCounter = 0
    s.allNodes = {}
    s.rootNode = new MoveNode(null, -1, -1, null)
    s.currentNodeId = s.rootNode.id
    s.version++
  },

  addMove(s, { x, y, color }) {
    const current = findNodeById(s.rootNode, s.currentNodeId)
    if (!current) return

    const newNode = current.makeMove(x, y, color)
    s.currentNodeId = newNode.id
    s.version++
  },

  goBack(s) {
    const current = findNodeById(s.rootNode, s.currentNodeId)
    if (current && current.parent) {
      s.currentNodeId = current.parent.id
      s.version++
    }
  },

  goToNode(s, id) {
    if (findNodeById(s.rootNode, id)) {
      s.currentNodeId = id
      s.version++
    }
  },

  deleteBranch(s, id) {
    const node = findNodeById(s.rootNode, id)
    if (!node || !node.parent) return

    const parent = node.parent
    const idx = parent.children.indexOf(node)
    if (idx !== -1) {
      parent.children.splice(idx, 1)
    }

    if (s.currentNodeId === id || isDescendant(s.rootNode, s.currentNodeId, node)) {
      s.currentNodeId = parent.id
    }
    s.version++
  },

  // 从JSON加载棋谱
  loadFromJSON(s, jsonData) {
    idCounter = 0
    s.allNodes = {}
    s.rootNode = MoveNode.fromJSON(jsonData)
    
    // 重建 allNodes 缓存，并找到最后一个真实落子节点。
    let lastNode = s.rootNode
    function addToCache(node) {
      s.allNodes[node.id] = node
      if (node.depth > lastNode.depth && node.x >= 0 && node.y >= 0) {
        lastNode = node
      }
      for (const child of node.children) {
        addToCache(child)
      }
    }
    addToCache(s.rootNode)
    
    // 设置当前节点为最后一个落子的节点
    s.currentNodeId = lastNode.id
    s.version++
  },

  // 获取整个树的JSON数据
  getTreeJSON(s) {
    if (!s.rootNode) return null
    return s.rootNode.toJSON()
  },
}

function isDescendant(root, targetId, ancestor) {
  const target = findNodeById(root, targetId)
  if (!target) return false
  let cur = target
  while (cur) {
    if (cur === ancestor) return true
    cur = cur.parent
  }
  return false
}

const actions = {
  init({ commit }) {
    commit('init')
  },

  recordMove({ commit }, { x, y, color }) {
    commit('addMove', { x, y, color })
  },

  goBack({ commit }) {
    commit('goBack')
  },

  goToNode({ commit }, id) {
    commit('goToNode', id)
  },

  deleteBranch({ commit }, id) {
    commit('deleteBranch', id)
  },

  loadFromJSON({ commit }, jsonData) {
    commit('loadFromJSON', jsonData)
  },

  getTreeJSON({ state }) {
    if (!state.rootNode) return null
    return state.rootNode.toJSON()
  },
}

export default {
  namespaced: true,
  state,
  getters,
  mutations,
  actions,
  NODE_SIZE,
  GAP_Y,
  PADDING,
  LABEL_WIDTH,
}
