<template>
  <group :title="$t('moveTree.title')">
    <div class="tree-toolbar">
      <div class="record-picker">
        <button class="tree-select-btn" @click="dropdownOpen = !dropdownOpen">
          {{ currentRecordTitle || '选择棋谱' }} <span class="caret">▾</span>
        </button>
        <div v-if="dropdownOpen" class="record-menu">
          <div
            v-for="(record, index) in records"
            :key="record.filename"
            class="record-option"
            :class="{ active: record.filename === currentRecordFilename }"
            @click="selectRecord(index)"
            @mousedown="startLongPress(record)"
            @mouseup="cancelLongPress"
            @mouseleave="cancelLongPress"
            @touchstart="startLongPress(record)"
            @touchend="cancelLongPress"
            @touchcancel="cancelLongPress"
          >
            <span class="record-title">{{ record.title }}</span>
            <button class="record-delete" @click.stop="confirmDeleteRecord(record)">删除</button>
          </div>
          <div v-if="records.length === 0" class="record-empty">暂无棋谱</div>
        </div>
      </div>
    </div>
    <div class="tree-scroll" ref="scroll">
      <canvas ref="canvas" @click="onCanvasClick" @dblclick="onCanvasDblClick"></canvas>
    </div>
  </group>
</template>

<script>
import { Group } from 'vux'
import { mapState, mapGetters } from 'vuex'
import recordService from '@/services/recordService'

const NODE_SIZE = 16
const GAP_Y = 36
const PADDING = 20
const LABEL_WIDTH = 28

export default {
  name: 'MoveTree',
  components: { Group },
  data() {
    return {
      records: [],
      currentRecordFilename: null,
      dropdownOpen: false,
      loadingRecord: false,
      saveTimer: null,
      longPressTimer: null,
      longPressHandled: false,
    }
  },
  computed: {
    ...mapState('moveTree', ['rootNode', 'currentNodeId', 'version']),
    ...mapState('settings', ['boardSize', 'rule']),
    ...mapGetters('moveTree', ['currentNode']),
    currentRecordTitle() {
      const record = this.records.find((r) => r.filename === this.currentRecordFilename)
      return record ? record.title : ''
    },
  },
  watch: {
    version() {
      this.$nextTick(() => this.renderTree())
      this.scheduleAutoSave()
    },
  },
  async mounted() {
    await this.refreshRecords()
    if (this.records.length > 0) await this.loadRecord(this.records[0])
    else await this.createRecord(recordService.formatDateName())
    this.$nextTick(() => this.renderTree())
  },
  beforeDestroy() {
    clearTimeout(this.saveTimer)
    clearTimeout(this.longPressTimer)
  },
  methods: {
    async refreshRecords() {
      this.records = await recordService.listRecords()
    },

    isOnCurrentPath(node) {
      let cur = this.currentNode
      while (cur) {
        if (cur.id === node.id) return true
        cur = cur.parent
      }
      return false
    },

    findNodeAt(node, px, py) {
      if (!node) return null
      const r = NODE_SIZE / 2
      const dx = px - node.renderX
      const dy = py - node.renderY
      if (Math.abs(dx) <= r && Math.abs(dy) <= r) return node
      for (const child of node.children) {
        const found = this.findNodeAt(child, px, py)
        if (found) return found
      }
      return null
    },

    onCanvasClick(e) {
      const canvas = this.$refs.canvas
      const rect = canvas.getBoundingClientRect()
      const scroll = this.$refs.scroll
      const px = e.clientX - rect.left + scroll.scrollLeft
      const py = e.clientY - rect.top + scroll.scrollTop

      const node = this.findNodeAt(this.rootNode, px, py)
      if (node) {
        this.$store.dispatch('moveTree/goToNode', node.id)
        this.$emit('node-selected', node)
      }
    },

    onCanvasDblClick(e) {
      const canvas = this.$refs.canvas
      const rect = canvas.getBoundingClientRect()
      const scroll = this.$refs.scroll
      const px = e.clientX - rect.left + scroll.scrollLeft
      const py = e.clientY - rect.top + scroll.scrollTop

      const node = this.findNodeAt(this.rootNode, px, py)
      if (node && node !== this.rootNode) {
        const xChars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('')
        const letter = xChars[node.x] || '?'
        const num = this.boardSize - node.y
        const color = node.color === 'black' ? '黑' : '白'
        const name = `${color} ${letter}${num}`

        if (confirm(`删除 ${name} 及其所有后续分支？`)) {
          this.$store.dispatch('moveTree/deleteBranch', node.id)
          this.$emit('node-selected', this.currentNode)
        }
      }
    },

    async createRecord(name) {
      const title = recordService.sanitizeName(name || recordService.formatDateName())
      const filename = recordService.filenameFromName(title)
      this.currentRecordFilename = filename
      this.$store.dispatch('moveTree/init')
      try {
        await this.saveCurrentRecord(true)
        await this.refreshRecords()
        this.currentRecordFilename = filename
        this.dropdownOpen = false
      } catch (e) {
        alert('新建棋谱失败: ' + e.message)
      }
    },

    async loadRecord(record) {
      if (!record) return
      this.loadingRecord = true
      try {
        const data = await recordService.loadRecord(record.filename)
        if (data) {
          this.currentRecordFilename = record.filename
          this.$store.dispatch('moveTree/loadFromJSON', data.tree)
          this.$emit('record-loaded')
        }
      } catch (e) {
        alert('读取失败: ' + e.message)
      } finally {
        this.loadingRecord = false
      }
    },

    async selectRecord(index) {
      if (this.longPressHandled) {
        this.longPressHandled = false
        return
      }
      await this.switchRecord(index)
    },

    async switchRecord(index) {
      if (index < 0 || index >= this.records.length) return
      this.dropdownOpen = false
      await this.saveCurrentRecord()
      await this.loadRecord(this.records[index])
    },

    scheduleAutoSave() {
      if (this.loadingRecord || !this.currentRecordFilename || !this.rootNode) return
      clearTimeout(this.saveTimer)
      this.saveTimer = setTimeout(() => this.saveCurrentRecord(), 600)
    },

    async saveCurrentRecord(showError = false) {
      if (!this.currentRecordFilename || !this.rootNode) return
      try {
        const treeData = this.$store.getters['moveTree/getTreeJSON']
        const metadata = recordService.createMetadata(this.boardSize, this.rule)
        const saved = await recordService.saveRecord(this.currentRecordFilename, treeData, metadata)
        this.currentRecordFilename = saved.filename
        const existing = this.records.find((r) => r.filename === saved.filename)
        if (existing) Object.assign(existing, saved)
        else this.records.unshift(saved)
        this.records.sort((a, b) => b.mtime - a.mtime)
      } catch (e) {
        if (showError) throw e
        alert('自动保存棋谱失败: ' + e.message)
      }
    },

    startLongPress(record) {
      this.cancelLongPress()
      this.longPressHandled = false
      this.longPressTimer = setTimeout(() => this.renameRecord(record), 650)
    },

    cancelLongPress() {
      clearTimeout(this.longPressTimer)
      this.longPressTimer = null
    },

    async renameRecord(record) {
      this.cancelLongPress()
      this.longPressHandled = true
      if (!record) return
      const nextName = prompt('重命名棋谱', record.title)
      if (!nextName || nextName.trim() === record.title) return
      try {
        const renamed = await recordService.renameRecord(record.filename, nextName)
        if (this.currentRecordFilename === record.filename) this.currentRecordFilename = renamed.filename
        await this.refreshRecords()
        await this.saveCurrentRecord()
      } catch (e) {
        alert('重命名失败: ' + e.message)
      }
    },

    async confirmDeleteRecord(record) {
      this.cancelLongPress()
      if (!record) return
      if (!confirm(`删除棋谱「${record.title}」？`)) return
      const deletingCurrent = record.filename === this.currentRecordFilename
      try {
        await recordService.deleteRecord(record.filename)
        await this.refreshRecords()
        if (deletingCurrent) {
          if (this.records.length > 0) {
            await this.loadRecord(this.records[0])
          } else {
            await this.createRecord(recordService.formatDateName())
          }
        }
      } catch (e) {
        alert('删除失败: ' + e.message)
      }
    },

    renderTree() {
      const canvas = this.$refs.canvas
      if (!canvas || !this.rootNode) return

      const ctx = canvas.getContext('2d')
      const dpr = window.devicePixelRatio || 1

      this.rootNode.calcSubtreeWidth()
      this.rootNode.assignPositions(LABEL_WIDTH + PADDING, PADDING)

      let maxX = 0, maxY = 0
      const calcSize = (node) => {
        const right = node.renderX + NODE_SIZE / 2 + PADDING
        const bottom = node.renderY + NODE_SIZE / 2 + PADDING
        if (right > maxX) maxX = right
        if (bottom > maxY) maxY = bottom
        node.children.forEach(c => calcSize(c))
      }
      calcSize(this.rootNode)

      canvas.width = maxX * dpr
      canvas.height = maxY * dpr
      canvas.style.width = maxX + 'px'
      canvas.style.height = maxY + 'px'
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, maxX, maxY)

      const maxDepth = this.rootNode.getMaxDepth()
      ctx.fillStyle = '#666'
      ctx.font = '10px sans-serif'
      ctx.textAlign = 'right'
      ctx.textBaseline = 'middle'
      for (let d = 1; d <= maxDepth; d++) {
        const y = PADDING + (d - 1) * GAP_Y + NODE_SIZE / 2
        ctx.fillText(d.toString(), LABEL_WIDTH - 4, y)
      }

      this.drawEdges(ctx, this.rootNode)
      this.drawNodes(ctx, this.rootNode)
    },

    drawEdges(ctx, node) {
      if (!node.children || node.children.length === 0) return

      for (const child of node.children) {
        const isPrimary = child === node.primaryChild
        const onPath = this.isOnCurrentPath(child)

        ctx.strokeStyle = (isPrimary || onPath) ? '#333' : '#bbb'
        ctx.lineWidth = (isPrimary || onPath) ? 2.0 : 1.2

        const startX = node.renderX
        const startY = node.renderY + NODE_SIZE / 2
        const endX = child.renderX
        const endY = child.renderY - NODE_SIZE / 2
        const midY = (startY + endY) / 2

        ctx.beginPath()
        ctx.moveTo(startX, startY)
        ctx.bezierCurveTo(startX, midY, endX, midY, endX, endY)
        ctx.stroke()

        this.drawEdges(ctx, child)
      }
    },

    drawNodes(ctx, node) {
      const isCurrent = node.id === this.currentNodeId
      const isRoot = node.parent === null
      const cx = node.renderX
      const cy = node.renderY
      const r = NODE_SIZE / 2

      let fillColor
      if (isRoot) fillColor = '#999'
      else if (node.color === 'black') fillColor = '#333'
      else fillColor = '#fff'

      ctx.beginPath()
      ctx.arc(cx, cy, r, 0, Math.PI * 2)
      ctx.fillStyle = fillColor
      ctx.fill()

      ctx.strokeStyle = isCurrent ? '#FF6D00' : '#666'
      ctx.lineWidth = isCurrent ? 2.5 : 1.2
      ctx.stroke()

      for (const child of node.children) this.drawNodes(ctx, child)
    },
  },
}
</script>

<style scoped>
.tree-toolbar {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  background: #f8f8f8;
  border-bottom: 1px solid #eee;
}
.record-picker {
  position: relative;
  width: 100%;
}
.tree-select-btn {
  width: 100%;
  padding: 7px 10px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid #ccc;
  border-radius: 4px;
  background: #fff;
  color: #333;
  text-align: left;
}
.caret {
  float: right;
}
.record-menu {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 2px);
  max-height: 260px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #ccc;
  border-radius: 4px;
  z-index: 20;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.record-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: #fff;
  color: #333;
  text-align: left;
  font-size: 12px;
  box-sizing: border-box;
}
.record-option.active,
.record-option:active {
  background: #eaf3fb;
}
.record-title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.record-delete {
  flex: 0 0 auto;
  border: 1px solid #e74c3c;
  border-radius: 3px;
  background: #fff;
  color: #e74c3c;
  font-size: 11px;
  padding: 2px 6px;
}
.record-delete:active {
  background: #fdecea;
}
.record-empty {
  padding: 10px;
  color: #888;
  font-size: 12px;
}
.tree-scroll {
  width: 100%;
  height: 400px;
  overflow: auto;
  background: #fff;
  border: 1px solid #ddd;
  border-radius: 4px;
  box-sizing: border-box;
}
canvas {
  display: block;
  cursor: pointer;
}
</style>
