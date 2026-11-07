// 棋盘和棋子的绘制。主界面和拍照结果页共用，颜色和线宽都来自设置里的 boardStyle。
export const paddingTop = 10
export const paddingBottom = 26
export const paddingX = 26

export function fillCircle(ctx, x, y, r) {
  const PI2 = 2 * Math.PI
  ctx.beginPath()
  ctx.arc(x, y, r, 0, PI2)
  ctx.fill()
}

export function drawBackground(ctx, style, w, h, noShadow) {
  ctx.save()
  if (!noShadow) {
    ctx.shadowOffsetX = ctx.shadowOffsetY = 2
    ctx.shadowBlur = paddingX / 2
    ctx.shadowColor = 'rgba(0, 0, 0, 0.2)'
  }
  ctx.fillStyle = style.boardColor
  ctx.fillRect(paddingX, paddingTop, w - paddingX * 2, h - paddingBottom - paddingTop)
  ctx.restore()
}

export function drawCoord(ctx, style, w, h, s, cs) {
  ctx.save()
  ctx.font = style.coordFontStyle + ' ' + Math.min(20, cs * 0.6) + 'px ' + style.coordFontFamily
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = style.coordColor

  // 左侧坐标
  ctx.save()
  ctx.translate(paddingX / 2, paddingTop + cs / 2)
  for (let i = 0; i < s; i++) {
    ctx.fillText(s - i, 0, cs * i, paddingX)
  }

  // 右侧坐标
  ctx.translate(w - paddingX, 0)
  for (let i = 0; i < s; i++) {
    ctx.fillText(s - i, 0, cs * i, paddingX)
  }
  ctx.restore()

  // 底部坐标
  ctx.translate(paddingX + cs / 2, h - paddingBottom / 2)
  for (let i = 0; i < s; i++) {
    ctx.fillText(String.fromCharCode('A'.charCodeAt(0) + i), cs * i, 0)
  }

  ctx.restore()
}

export function drawBoard(ctx, style, s, cs) {
  let si = s - 1

  ctx.save()
  ctx.strokeStyle = ctx.fillStyle = style.lineColor
  ctx.translate(paddingX + cs / 2, paddingTop + cs / 2)
  ctx.scale(cs, cs)

  // 网格线
  ctx.lineWidth = style.lineWidth
  ctx.beginPath()
  for (let i = 1; i < si; i++) {
    ctx.moveTo(i, 0)
    ctx.lineTo(i, si)
    ctx.moveTo(0, i)
    ctx.lineTo(si, i)
  }
  ctx.stroke()

  // 画边框
  ctx.lineWidth = style.lineWidth * 2.5
  ctx.beginPath()
  ctx.moveTo(0, 0)
  ctx.lineTo(si, 0)
  ctx.lineTo(si, si)
  ctx.lineTo(0, si)
  ctx.closePath()
  ctx.stroke()

  // 画星位
  let starPad = Math.floor(s / 5)
  let starCenter = Math.floor(s / 2)
  let starRadius = style.starRadiusScale
  fillCircle(ctx, starPad, starPad, starRadius)
  fillCircle(ctx, si - starPad, starPad, starRadius)
  fillCircle(ctx, starPad, si - starPad, starRadius)
  fillCircle(ctx, si - starPad, si - starPad, starRadius)
  fillCircle(ctx, starCenter, starCenter, starRadius)

  ctx.restore()
}

// 颜色分开给的棋子列表，坐标是 [x, y]
export function drawStones(ctx, style, cs, blacks, whites) {
  let radius = style.pieceScale / 2
  ctx.save()
  ctx.translate(paddingX + cs / 2, paddingTop + cs / 2)
  ctx.scale(cs, cs)

  ctx.lineWidth = style.pieceStrokeWidth
  ctx.fillStyle = style.pieceBlack
  ctx.strokeStyle = style.pieceStrokeBlack
  for (let pos of blacks) {
    fillCircle(ctx, pos[0], pos[1], radius)
    if (style.pieceStrokeWidth > 0) ctx.stroke()
  }

  ctx.fillStyle = style.pieceWhite
  ctx.strokeStyle = style.pieceStrokeWhite
  for (let pos of whites) {
    fillCircle(ctx, pos[0], pos[1], radius)
    if (style.pieceStrokeWidth > 0) ctx.stroke()
  }

  ctx.restore()
}

// 按落子顺序排列的棋子：奇数手黑，偶数手白
export function drawPiece(ctx, style, cs, position, end) {
  const stones = position.slice(0, Math.min(end, position.length))
  drawStones(ctx, style, cs, stones.filter((_, i) => i % 2 === 0), stones.filter((_, i) => i % 2 === 1))
}
