import Vue from 'vue'
import Vuex from 'vuex'
import ai from './modules/ai'
import position from './modules/position'
import settings from './modules/settings'
import moveTree from './modules/moveTree'

Vue.use(Vuex)

export default new Vuex.Store({
  modules: {
    ai,
    position,
    settings,
    moveTree,
  },
  state: {
    screenWidth: document.documentElement.clientWidth, // 屏幕宽度
    screenHeight: document.documentElement.clientHeight, // 屏幕高度
    isOnIOSBrowser:
      (/iPad|iPhone|iPod/.test(navigator.platform) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) &&
      !window.MSStream,
    maxThreads: 1,
    supportThreads: false,
    supportSimd: false,
    supportRelaxedSimd: false,
  },
  getters: {
    boardCanvasWidth(state) {
      const MinBoardWidth = 300
      const BottomPadding = 200
      if (state.screenWidth >= 1024 && state.screenWidth / state.screenHeight >= 4 / 3) {
        return Math.max(
          Math.min(state.screenWidth / 2 - 40, state.screenHeight - 80),
          MinBoardWidth
        )
      }
      return Math.max(
        Math.min(state.screenWidth, state.screenHeight - BottomPadding),
        MinBoardWidth
      )
    },
  },
  mutations: {
    setScreenSize(state, payload) {
      state.screenWidth = payload.width
      state.screenHeight = payload.height
    },
    setBrowserCapability(state, { maxThreads, supportThreads, supportSimd, supportRelaxedSimd }) {
      state.maxThreads = maxThreads
      state.supportThreads = supportThreads
      state.supportSimd = supportSimd
      state.supportRelaxedSimd = supportRelaxedSimd
    },
  },
  actions: {
    async getBrowserCapabilities({ commit, rootState }) {
      // Native Rapfi exposes a configurable worker count. Keep this capability
      // independent from browser/WebAssembly feature detection. The Android
      // bridge may later report a stricter limit.
      const maxThreads = Math.max(1, Number(navigator.hardwareConcurrency) || 1)
      const supportThreads = true
      const supportSimd = false
      const supportRelaxedSimd = false
      commit('setBrowserCapability', {
        maxThreads,
        supportThreads,
        supportSimd,
        supportRelaxedSimd,
      })
      if (rootState.settings.rapfiThreads === null) {
        commit('settings/setValue', {
          key: 'rapfiThreads',
          value: Math.max(1, Math.min(64, Math.ceil((navigator.hardwareConcurrency || 2) / 2))),
        })
      }
    },
  },
  strict: process.env.NODE_ENV !== 'production',
})
