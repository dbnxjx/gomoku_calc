// 拍照结果页的棋盘模型。19 路棋盘照原样编辑；更小的都放到 15×15 里居中，15 到 19 之间的放到 19×19。
// 棋子存在一块不限大小的平面上，界面只是平面上的一个窗口。方向键移动的是窗口，
// 所以移出窗口的棋子不会丢，反方向移回来还在。
export const EMPTY = 0
export const BLACK = 1
export const WHITE = 2

export function viewSizeFor(boardSize) {
  return boardSize > 15 ? 19 : 15
}

export class BoardView {
  // state: n 行 n 列的二维数组，0 空 1 黑 2 白
  constructor(state) {
    const n = state.length
    this.viewSize = viewSizeFor(n)
    this.stones = new Map()
    state.forEach((row, y) => row.forEach((color, x) => {
      if (color > 0) this.stones.set(`${y},${x}`, color)
    }))
    const offset = -Math.floor((this.viewSize - n) / 2)
    this.top = offset
    this.left = offset
  }

  view() {
    const out = Array.from({ length: this.viewSize }, () => new Array(this.viewSize).fill(EMPTY))
    this.stones.forEach((color, key) => {
      const [y, x] = key.split(',').map(Number)
      const i = y - this.top
      const j = x - this.left
      if (i >= 0 && j >= 0 && i < this.viewSize && j < this.viewSize) out[i][j] = color
    })
    return out
  }

  // 把所有棋子向下移 dy、向右移 dx
  shift(dy, dx) {
    this.top -= dy
    this.left -= dx
  }

  // 点到已有棋子就删除，点到空位就放下当前选择的颜色
  tap(i, j, adding) {
    const key = `${i + this.top},${j + this.left}`
    if (this.stones.has(key)) this.stones.delete(key)
    else this.stones.set(key, adding)
  }
}

// 真实棋局黑先交替：黑 = 白（下一手黑），或黑 = 白 + 1（下一手白）
export function checkCounts(black, white) {
  if (black === white) return { valid: true, nextPlayer: 'black' }
  if (black === white + 1) return { valid: true, nextPlayer: 'white' }
  return { valid: false, nextPlayer: null }
}

export function hasFive(view) {
  const size = view.length
  const dirs = [[1, 0], [0, 1], [1, 1], [1, -1]]
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const color = view[y][x]
      if (color === EMPTY) continue
      for (const [dx, dy] of dirs) {
        let count = 1
        while (count < 5) {
          const ny = y + dy * count
          const nx = x + dx * count
          if (ny < 0 || nx < 0 || ny >= size || nx >= size || view[ny][nx] !== color) break
          count++
        }
        if (count >= 5) return true
      }
    }
  }
  return false
}

// 视图转成棋盘坐标 [x, y]
export function viewToStones(view) {
  const black = []
  const white = []
  view.forEach((row, y) => row.forEach((color, x) => {
    if (color === BLACK) black.push([x, y])
    else if (color === WHITE) white.push([x, y])
  }))
  return { black, white }
}
