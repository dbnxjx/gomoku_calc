// 拍照识别的原生接口，实现见 android 的 com.gomocalc.app.recognition。
import { Capacitor, registerPlugin } from '@capacitor/core'

const BoardRecognition = registerPlugin('BoardRecognition')

// source: camera 或 gallery
export function capture(source) {
  return BoardRecognition.capture({ source })
}

export function recognizeWithQuad(imagePath, quad) {
  return BoardRecognition.recognize({ imagePath, quad })
}

// 打开后系统返回键不再退出应用，而是触发 onBackPressed 注册的回调
export function interceptBack(enabled) {
  return BoardRecognition.interceptBack({ enabled })
}

export function onBackPressed(handler) {
  return BoardRecognition.addListener('backPressed', handler)
}

// 本地文件路径转成网页能加载的地址
export function fileUrl(path) {
  return Capacitor.convertFileSrc(path)
}

export function parseBoard(rows) {
  return rows.map((row) => row.split('').map((c) => '.BW'.indexOf(c)))
}
