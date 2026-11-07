<template>
  <div class="recognize">
    <!-- 结果页：裁切图和可编辑的棋盘 -->
    <div v-if="!cropping" class="page">
      <div class="crop-row">
        <img class="crop-image" :src="cropUrl" alt="裁切后的棋盘" @click="enlarged = true" />
        <div class="crop-info">
          <div class="info-title">识别结果（{{ result.n }} 路）</div>
          <div v-for="warning in result.warnings" :key="warning" class="warning">{{ warning }}</div>
          <button class="plain-button" @click="openCrop">手动裁切</button>
        </div>
      </div>

      <canvas ref="board" class="board" :style="{ width: `${canvasWidth}px`, height: `${canvasHeight}px` }" @click="onBoardClick"></canvas>

      <div class="tools">
        <div class="segmented">
          <button :class="{ active: adding === 1 }" @click="adding = 1"><span class="dot dot-black"></span>黑</button>
          <button :class="{ active: adding === 2 }" @click="adding = 2"><span class="dot dot-white"></span>白</button>
        </div>
        <div class="arrows">
          <button aria-label="上移" @click="shift(-1, 0)"><i class="fa fa-arrow-up"></i></button>
          <button aria-label="下移" @click="shift(1, 0)"><i class="fa fa-arrow-down"></i></button>
          <button aria-label="左移" @click="shift(0, -1)"><i class="fa fa-arrow-left"></i></button>
          <button aria-label="右移" @click="shift(0, 1)"><i class="fa fa-arrow-right"></i></button>
          <button class="compare" :class="{ active: comparing }" @pointerdown.prevent="comparing = true" @pointerup="comparing = false"
            @pointercancel="comparing = false" @pointerleave="comparing = false" @contextmenu.prevent>按住对比</button>
        </div>
      </div>

      <div class="status" :class="{ invalid: !check.valid }">
        黑 {{ counts.black }} · 白 {{ counts.white }}
        <template v-if="check.valid"> · 下一手{{ check.nextPlayer === 'black' ? '黑' : '白' }}</template>
        <template v-else>：黑子应等于白子，或比白子多一颗</template>
      </div>
      <div v-if="hiddenCount > 0" class="note">另有 {{ hiddenCount }} 颗棋子移到了棋盘外，不会放上棋盘，可用方向键移回</div>
      <div v-if="five" class="note">局面里已有五连，棋局已结束</div>
      <div v-if="viewSize > 15 && engineType === 'KATAGO'" class="note">KataGo 只支持 15 路，确定后会切换到 Rapfi</div>

      <div class="actions">
        <button class="plain-button" @click="$emit('close')">取消</button>
        <button class="plain-button" @click="$emit('retake')">{{ retakeLabel }}</button>
        <button class="primary-button" :disabled="!check.valid" @click="confirm">确定</button>
      </div>

      <div v-if="enlarged" class="enlarged" @click="enlarged = false">
        <img :src="cropUrl" alt="裁切后的棋盘" />
      </div>
    </div>

    <!-- 手动裁切：回到原图。单指拖绿点调角点、拖空白移动画面，双指缩放 -->
    <div v-else class="crop-page">
      <div ref="stage" class="stage" @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerUp">
        <img class="photo" :src="photoUrl" :style="photoStyle" alt="原图" draggable="false" />
        <svg v-if="stage" class="overlay" :width="stage.width" :height="stage.height">
          <polygon class="quad-line" :points="quadPoints" />
          <template v-for="(handle, index) in handles">
            <line :key="`line${index}`" class="guide" :x1="handle.cornerX" :y1="handle.cornerY" :x2="handle.x" :y2="handle.y" />
            <circle :key="`dot${index}`" class="handle-dot" :cx="handle.x" :cy="handle.y" :r="HANDLE_RADIUS" />
          </template>
        </svg>
      </div>
      <div class="crop-bar">
        <div v-if="busy" class="crop-busy">正在识别…</div>
        <button class="dark-button" :disabled="busy" @click="cropping = false">取消</button>
        <button class="dark-button" :disabled="busy" @click="applyCrop">确定裁切</button>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { BoardView, BLACK, WHITE, checkCounts, hasFive, viewToStones } from '@/services/boardView'
import { fileUrl, parseBoard, recognizeWithQuad } from '@/services/boardRecognition'
import { paddingTop, paddingBottom, paddingX, drawBackground, drawCoord, drawBoard, drawStones } from '@/components/boardDraw'

const HANDLE_RADIUS = 11
const HANDLE_DISTANCE = 64 // 绿点离角点的距离，手指按住绿点时挡不到角点
const HANDLE_TOUCH = 40 // 手指落在绿点这个范围内就算按住
const MAX_ZOOM = 8

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

export default {
  name: 'RecognizeDialog',
  props: {
    // 原生识别的结果
    initial: { type: Object, required: true },
    retakeLabel: { type: String, default: '重拍' },
  },
  data() {
    // 不放进返回值：棋子改动由 revision 通知界面，避免 Vue 监听 Map。监听器初始化时就要用到，不能等到 created。
    this.boardView = new BoardView(parseBoard(this.initial.board))
    // 手指的位置不用触发界面更新
    this.pointers = new Map()
    this.gesture = null
    return {
      HANDLE_RADIUS,
      result: this.initial,
      adding: BLACK,
      revision: 0,
      enlarged: false,
      comparing: false,
      cropping: false,
      busy: false,
      draft: [],
      stage: null,
      // 屏幕坐标 = 图片坐标 * k + (x, y)
      camera: { k: 1, x: 0, y: 0, fit: 1 },
      dragOffset: null,
      dragIndex: null,
    }
  },
  computed: {
    ...mapState('settings', ['boardStyle', 'showCoord', 'engineType']),
    cropUrl() {
      return fileUrl(this.result.cropPath)
    },
    photoUrl() {
      return fileUrl(this.result.imagePath)
    },
    viewState() {
      return this.revision >= 0 ? this.boardView.view() : []
    },
    viewSize() {
      return this.viewState.length
    },
    counts() {
      const { black, white } = viewToStones(this.viewState)
      return { black: black.length, white: white.length }
    },
    check() {
      return checkCounts(this.counts.black, this.counts.white)
    },
    five() {
      return hasFive(this.viewState)
    },
    hiddenCount() {
      return this.revision >= 0 ? this.boardView.stones.size - this.counts.black - this.counts.white : 0
    },
    canvasWidth() {
      const width = document.documentElement.clientWidth
      const height = document.documentElement.clientHeight
      return Math.min(width, Math.max(300, height - 290))
    },
    canvasHeight() {
      return this.canvasWidth - 2 * paddingX + paddingBottom + paddingTop
    },
    photoStyle() {
      const { k, x, y } = this.camera
      return {
        width: `${this.result.width}px`,
        height: `${this.result.height}px`,
        transform: `translate(${x}px, ${y}px) scale(${k})`,
      }
    },
    corners() {
      const { k, x, y } = this.camera
      return this.draft.map((point) => ({ x: point[0] * k + x, y: point[1] * k + y }))
    },
    quadPoints() {
      return this.corners.map((point) => `${point.x},${point.y}`).join(' ')
    },
    handles() {
      const corners = this.corners
      const count = corners.length
      const cx = corners.reduce((sum, point) => sum + point.x, 0) / count
      const cy = corners.reduce((sum, point) => sum + point.y, 0) / count
      return corners.map((corner, index) => {
        let offset = index === this.dragIndex ? this.dragOffset : null
        if (!offset) {
          // 沿对角线朝外；放不下（出屏幕）时改为朝里
          const length = Math.hypot(corner.x - cx, corner.y - cy) || 1
          const ux = (corner.x - cx) / length
          const uy = (corner.y - cy) / length
          const outX = corner.x + ux * HANDLE_DISTANCE
          const outY = corner.y + uy * HANDLE_DISTANCE
          const fits = this.stage && outX > HANDLE_RADIUS && outY > HANDLE_RADIUS
            && outX < this.stage.width - HANDLE_RADIUS && outY < this.stage.height - HANDLE_RADIUS
          const sign = fits ? 1 : -1
          offset = { x: ux * HANDLE_DISTANCE * sign, y: uy * HANDLE_DISTANCE * sign }
        }
        return { cornerX: corner.x, cornerY: corner.y, x: corner.x + offset.x, y: corner.y + offset.y, offset }
      })
    },
  },
  mounted() {
    this.loadCropImage()
    this.draw()
  },
  watch: {
    comparing() {
      this.draw()
    },
    initial(next) {
      this.result = next
      this.loadResult(next)
      this.cropping = false
      this.enlarged = false
      this.$nextTick(() => this.draw())
    },
    viewState() {
      this.$nextTick(() => this.draw())
    },
    cropping(open) {
      if (!open) this.$nextTick(() => this.draw())
    },
  },
  methods: {
    // 系统返回键：一层一层退，最后退出识别
    handleBack() {
      if (this.enlarged) this.enlarged = false
      else if (this.cropping) {
        if (!this.busy) this.cropping = false
      } else this.$emit('close')
    },

    loadResult(result) {
      this.boardView = new BoardView(parseBoard(result.board))
      this.revision += 1
      this.loadCropImage()
    },

    // 对比用的裁切图提前加载，按下按钮时才能马上显示
    loadCropImage() {
      const image = new Image()
      image.onload = () => {
        if (this.comparing) this.draw()
      }
      image.src = this.cropUrl
      this.cropImage = image
    },

    // 裁切图四周各留半格，所以格子能和棋盘对齐；移动过棋子时图也跟着移，保持棋子和圈对得上
    drawComparison(ctx, width, cell) {
      const boardWidth = width - 2 * paddingX
      ctx.save()
      ctx.beginPath()
      ctx.rect(paddingX, paddingTop, boardWidth, boardWidth)
      ctx.clip()
      const size = this.result.n * cell
      ctx.drawImage(this.cropImage, paddingX - this.boardView.left * cell, paddingTop - this.boardView.top * cell, size, size)
      ctx.lineWidth = Math.max(1.5, cell * 0.09)
      this.viewState.forEach((row, y) => row.forEach((color, x) => {
        if (!color) return
        ctx.strokeStyle = color === BLACK ? '#ff0000' : '#0050ff'
        ctx.beginPath()
        ctx.arc(paddingX + cell / 2 + x * cell, paddingTop + cell / 2 + y * cell, cell * 0.42, 0, Math.PI * 2)
        ctx.stroke()
      }))
      ctx.restore()
    },

    draw() {
      const canvas = this.$refs.board
      if (!canvas) return
      const ratio = window.devicePixelRatio || 1
      const width = this.canvasWidth
      const height = this.canvasHeight
      canvas.width = width * ratio
      canvas.height = height * ratio
      const ctx = canvas.getContext('2d')
      ctx.scale(ratio, ratio)
      const cell = (width - 2 * paddingX) / this.viewSize
      drawBackground(ctx, this.boardStyle, width, height, false)
      if (this.showCoord) drawCoord(ctx, this.boardStyle, width, height, this.viewSize, cell)
      if (this.comparing && this.cropImage && this.cropImage.naturalWidth > 0) {
        this.drawComparison(ctx, width, cell)
        return
      }
      drawBoard(ctx, this.boardStyle, this.viewSize, cell)
      const { black, white } = viewToStones(this.viewState)
      drawStones(ctx, this.boardStyle, cell, black, white)
    },

    onBoardClick(event) {
      if (this.comparing) return
      const rect = event.target.getBoundingClientRect()
      const cell = (rect.width - 2 * paddingX) / this.viewSize
      const j = Math.floor((event.clientX - rect.left - paddingX) / cell)
      const i = Math.floor((event.clientY - rect.top - paddingTop) / cell)
      if (i < 0 || j < 0 || i >= this.viewSize || j >= this.viewSize) return
      this.boardView.tap(i, j, this.adding === BLACK ? BLACK : WHITE)
      this.revision += 1
    },

    shift(dy, dx) {
      this.boardView.shift(dy, dx)
      this.revision += 1
    },

    confirm() {
      if (!this.check.valid) return
      this.$emit('confirm', { ...viewToStones(this.viewState), size: this.viewSize })
    },

    openCrop() {
      this.draft = this.result.quad.map((point) => [...point])
      this.stage = null
      this.dragIndex = null
      this.dragOffset = null
      this.pointers.clear()
      this.gesture = null
      this.cropping = true
      this.$nextTick(() => {
        const box = this.$refs.stage.getBoundingClientRect()
        this.stage = { width: box.width, height: box.height, left: box.left, top: box.top }
        const fit = Math.min(box.width / this.result.width, box.height / this.result.height)
        this.camera = this.clampCamera(fit, 0, 0, fit)
      })
    },

    // 图片比屏幕小的方向居中；比屏幕大的方向不许露出边
    clampCamera(k, x, y, fit) {
      const place = (position, size, room) => {
        if (size <= room) return (room - size) / 2
        return Math.min(0, Math.max(room - size, position))
      }
      return {
        k,
        fit,
        x: place(x, this.result.width * k, this.stage.width),
        y: place(y, this.result.height * k, this.stage.height),
      }
    },

    localPoint(event) {
      return { x: event.clientX - this.stage.left, y: event.clientY - this.stage.top }
    },

    onPointerDown(event) {
      if (!this.stage || this.busy) return
      if (this.$refs.stage.setPointerCapture) this.$refs.stage.setPointerCapture(event.pointerId)
      this.pointers.set(event.pointerId, this.localPoint(event))
      if (this.pointers.size === 1) this.beginSingle(this.localPoint(event))
      else if (this.pointers.size === 2) this.beginPinch()
    },

    beginSingle(point) {
      let hit = -1
      let nearest = HANDLE_TOUCH
      this.handles.forEach((handle, index) => {
        const d = distance(point, handle)
        if (d < nearest) {
          nearest = d
          hit = index
        }
      })
      if (hit >= 0) {
        this.dragIndex = hit
        this.dragOffset = this.handles[hit].offset
        // 手指不一定正好按在绿点中心，拖动时保持这个差值，点才不会跳
        this.gesture = { type: 'drag', grabX: point.x - this.handles[hit].x, grabY: point.y - this.handles[hit].y }
      } else {
        this.gesture = { type: 'pan', lastX: point.x, lastY: point.y }
      }
    },

    beginPinch() {
      this.endDrag()
      const [a, b] = [...this.pointers.values()]
      this.gesture = {
        type: 'pinch',
        distance: Math.max(distance(a, b), 1),
        midX: (a.x + b.x) / 2,
        midY: (a.y + b.y) / 2,
        k: this.camera.k,
        x: this.camera.x,
        y: this.camera.y,
      }
    },

    onPointerMove(event) {
      if (!this.pointers.has(event.pointerId) || !this.gesture) return
      const point = this.localPoint(event)
      this.pointers.set(event.pointerId, point)
      const { k, x, y, fit } = this.camera
      const gesture = this.gesture
      if (gesture.type === 'pinch') {
        const [a, b] = [...this.pointers.values()]
        const scale = distance(a, b) / gesture.distance
        const nextK = Math.max(fit, Math.min(fit * MAX_ZOOM, gesture.k * scale))
        // 捏合中心下的那个图片点保持在两指中间
        const imageX = (gesture.midX - gesture.x) / gesture.k
        const imageY = (gesture.midY - gesture.y) / gesture.k
        const midX = (a.x + b.x) / 2
        const midY = (a.y + b.y) / 2
        this.camera = this.clampCamera(nextK, midX - imageX * nextK, midY - imageY * nextK, fit)
      } else if (gesture.type === 'drag') {
        const offset = this.dragOffset
        const cornerX = (point.x - gesture.grabX - offset.x - x) / k
        const cornerY = (point.y - gesture.grabY - offset.y - y) / k
        this.$set(this.draft, this.dragIndex, [
          Math.max(0, Math.min(this.result.width - 1, cornerX)),
          Math.max(0, Math.min(this.result.height - 1, cornerY)),
        ])
      } else {
        this.camera = this.clampCamera(k, x + point.x - gesture.lastX, y + point.y - gesture.lastY, fit)
        gesture.lastX = point.x
        gesture.lastY = point.y
      }
    },

    onPointerUp(event) {
      this.pointers.delete(event.pointerId)
      this.endDrag()
      if (this.pointers.size === 1) {
        // 从双指变回单指：剩下的手指只用来移动画面，不去碰绿点
        const [point] = [...this.pointers.values()]
        this.gesture = { type: 'pan', lastX: point.x, lastY: point.y }
      } else if (this.pointers.size === 0) {
        this.gesture = null
      }
    },

    endDrag() {
      this.dragIndex = null
      this.dragOffset = null
    },

    async applyCrop() {
      this.busy = true
      try {
        const next = await recognizeWithQuad(this.result.imagePath, this.draft)
        this.result = next
        this.loadResult(next)
        this.cropping = false
      } catch (error) {
        this.$vux.alert.show_i18n({ title: '识别失败', content: String(error.message || error) })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="less" scoped>
.recognize {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1000;
  background: #fbf9fe;
  overflow-y: auto;
}

.page {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100%;
  box-sizing: border-box;
  padding: 8px 0;
}

.crop-row {
  display: flex;
  align-self: stretch;
  gap: 10px;
  margin: 0 8px 8px;
}

.crop-image {
  flex: 0 0 auto;
  width: 96px;
  height: 96px;
  border: 1px solid #ccc;
}

.crop-info {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
}

.info-title {
  font-size: 14px;
  font-weight: bold;
  color: #333;
  margin-bottom: 4px;
}

.warning {
  color: #c0392b;
  margin-bottom: 4px;
}

.board {
  display: block;
  touch-action: manipulation;
}

.tools {
  display: flex;
  justify-content: space-between;
  align-self: stretch;
  margin: 8px 8px 0;
}

.segmented,
.arrows {
  display: flex;
}

.segmented button,
.arrows button {
  height: 40px;
  min-width: 40px;
  padding: 0 8px;
  font-size: 14px;
  color: #333;
  background: #f5f5f5;
  border: 1px solid #ccc;
}

.segmented button + button,
.arrows button + button {
  border-left: none;
}

.segmented button.active,
.arrows button.compare.active {
  color: #fff;
  background: #229954;
  border-color: #229954;
}

.arrows button.compare {
  margin-left: 6px;
  border-left: 1px solid #ccc;
  white-space: nowrap;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  margin-right: 4px;
  border: 1px solid #333;
  border-radius: 50%;
  vertical-align: -1px;
}

.dot-black {
  background: #1a1a1a;
}

.dot-white {
  background: #fff;
}

.status {
  align-self: stretch;
  margin: 8px 8px 0;
  font-size: 14px;
  color: #1e8449;
  text-align: center;
}

.status.invalid {
  color: #c0392b;
}

/* 和上面的黑白数量同样的字号，颜色和识别提示相同 */
.note {
  align-self: stretch;
  margin: 4px 8px 0;
  font-size: 14px;
  color: #c0392b;
  text-align: center;
}

.actions {
  display: flex;
  align-self: stretch;
  gap: 8px;
  margin: 10px 8px 0;
}

.actions button {
  flex: 1 1 0;
  height: 44px;
}

.plain-button,
.primary-button {
  padding: 0 12px;
  height: 36px;
  font-size: 15px;
  border-radius: 4px;
}

.plain-button {
  color: #333;
  background: #f5f5f5;
  border: 1px solid #ccc;
}

.primary-button {
  color: #fff;
  background: #229954;
  border: 1px solid #229954;
}

.primary-button:disabled {
  color: #eee;
  background: #a9b8ad;
  border-color: #a9b8ad;
}

.enlarged {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;

  img {
    max-width: 100%;
    max-height: 100%;
  }
}

.crop-page {
  position: absolute;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  background: #000;
}

.stage {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  touch-action: none;
  background: #000;
}

.photo {
  position: absolute;
  left: 0;
  top: 0;
  max-width: none;
  transform-origin: 0 0;
  pointer-events: none;
  user-select: none;
}

.overlay {
  position: absolute;
  left: 0;
  top: 0;
  pointer-events: none;
}

.quad-line {
  fill: none;
  stroke: #00e650;
  stroke-width: 1.5;
}

.guide {
  stroke: #00e650;
  stroke-width: 1.5;
  stroke-dasharray: 5 4;
}

.handle-dot {
  fill: #00e650;
  stroke: #fff;
  stroke-width: 1;
}

.crop-bar {
  position: relative;
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  padding: 8px;
  background: #000;
}

.crop-busy {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 100%;
  padding-bottom: 8px;
  font-size: 13px;
  color: #fff;
  text-align: center;
}

.dark-button {
  flex: 1 1 0;
  height: 44px;
  font-size: 15px;
  color: #fff;
  background: #555;
  border: none;
  border-radius: 4px;
}

.dark-button:disabled {
  color: #aaa;
}
</style>
