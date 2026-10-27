<template>
  <div id="app" style="height: 100%">
    <drawer :show.sync="drawerOpen" show-mode="overlay" placement="right" :drawer-style="{
      'background-color': 'rgba(255,255,255,1.0)',
      width: 'min(75%, 600px)',
      height: '100%',
    }">
      <!-- drawer content -->
      <div ref="drawer" slot="drawer" class="drawer">
        <div class="drawer-log-title">引擎日志</div>
        <div class="drawer-log-lines">
          <div v-for="(msg, index) in messages" :key="index" class="drawer-log-line">{{ msg }}</div>
        </div>
      </div>

      <view-box ref="viewBox" :body-padding-top="showHeader ? '46px' : '0'" body-padding-bottom="50px">
        <!-- header content -->
        <x-header v-if="showHeader" slot="header" class="header-bar" :left-options="{ showBack: false }"
          :right-options="{ showMore: false }" @on-click-more="showMessages">
          {{ $t('appName') }}
          <a v-if="route.path !== '/settings' && route.path !== '/about'" slot="right" class="needsclick"
            aria-label="引擎日志" title="引擎日志" @click="showMessages">
            <i class="fa fa-list fa-lg" aria-hidden="true" style="color: #d6eaf8"></i>
          </a>
        </x-header>

        <!-- main content -->
        <keep-alive>
          <router-view class="router-view"></router-view>
        </keep-alive>

        <!-- tabbar content -->
        <tabbar class="app-tabber" slot="bottom" style="position: fixed">
          <tabbar-item link="/" :selected="route.path !== '/settings' && route.path !== '/about'">
            <x-icon slot="icon" type="ios-grid-view-outline"></x-icon>
            <x-icon slot="icon-active" type="ios-grid-view-outline" class="tabber-icon-active"></x-icon>
            <span slot="label">{{ $t('tabbar.game') }}</span>
          </tabbar-item>
          <tabbar-item link="/settings" :selected="route.path === '/settings'">
            <x-icon slot="icon" type="ios-cog-outline"></x-icon>
            <x-icon slot="icon-active" type="ios-cog-outline" class="tabber-icon-active"></x-icon>
            <span slot="label">{{ $t('tabbar.settings') }}</span>
          </tabbar-item>
          <tabbar-item link="/about" :selected="route.path === '/about'">
            <x-icon slot="icon" type="ios-information-outline"></x-icon>
            <x-icon slot="icon-active" type="ios-information-outline" class="tabber-icon-active"></x-icon>
            <span slot="label">{{ $t('tabbar.about') }}</span>
          </tabbar-item>
        </tabbar>
      </view-box>
    </drawer>
  </div>
</template>

<script>
import { ViewBox, XHeader, Tabbar, TabbarItem, Drawer } from 'vux'
import { mapState, mapMutations, mapActions } from 'vuex'

export default {
  components: {
    ViewBox,
    XHeader,
    Tabbar,
    TabbarItem,
    Drawer,
  },
  data: function () {
    return {
      showHeader: true,
      drawerOpen: false,
      settingsLoaded: false,
      initialEngineStarted: false,
    }
  },
  computed: {
    ...mapState('settings', ['language', 'configIndex', 'engineType']),
    ...mapState('ai', ['messages']),
    route() {
      return this.$route
    },
  },
  methods: {
    ...mapMutations('settings', ['setValue']),
    ...mapActions('ai', ['initEngine']),
    ...mapActions('settings', ['readCookies']),
    ...mapActions(['getBrowserCapabilities']),
    showMessages: function () {
      this.drawerOpen = !this.drawerOpen
    },
    scrollLogsToLatest() {
      this.$nextTick(() => {
        const drawer = this.$refs.drawer
        if (drawer) drawer.scrollTop = drawer.scrollHeight
      })
    },
  },
  watch: {
    messages: {
      handler() {
        if (this.drawerOpen) this.scrollLogsToLatest()
      },
      deep: true,
    },
    drawerOpen(open) {
      if (open) this.scrollLogsToLatest()
    },
    language(newValue) {
      this.$i18n.locale = newValue
    },
    engineType(newValue, oldValue) {
      if (this.settingsLoaded && oldValue && newValue !== oldValue) this.initEngine(true)
    },
  },
  created() {
    this.readCookies()
    this.getBrowserCapabilities()
    if (!this.language) {
      this.setValue({ key: 'language', value: this.$i18n.locale })
    } else {
      this.$i18n.locale = this.language
    }
    this.$nextTick(() => { this.settingsLoaded = true })
  },
  mounted() {
    const _this = this
    // Remove registrations/caches left by older Web/WASM builds so Android
    // always opens the current native-engine page.
    if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then((registrations) => {
        registrations.forEach((registration) => registration.unregister())
      })
    }
    if (window.caches && window.caches.keys) {
      window.caches.keys().then((keys) => keys.forEach((key) => window.caches.delete(key)))
    }
    // 加入 i18n 版本的 $vux.alert, $vux.confirm
    this.$vux.alert.show_i18n = function (options) {
      options.buttonText = _this.$t('common.ok')
      _this.$vux.alert.show(options)
    }
    this.$vux.confirm.show_i18n = function (options) {
      options.confirmText = _this.$t('common.confirm')
      options.cancelText = _this.$t('common.cancel')
      _this.$vux.confirm.show(options)
    }
    this.$vux.confirm.prompt_i18n = function (value, options) {
      options.confirmText = _this.$t('common.confirm')
      options.cancelText = _this.$t('common.cancel')
      _this.$vux.confirm.prompt(value, options)
    }

    window.onresize = function () {
      // 定义窗口大小变更通知事件
      _this.$store.commit('setScreenSize', {
        width: document.documentElement.clientWidth,
        height: document.documentElement.clientHeight,
      })
    }

    // The browser WASM engine was removed. This only probes the future native
    // Rapfi plugin and leaves the page usable while it is absent.
    _this.$nextTick(() => {
      if (this.initialEngineStarted) return
      this.initialEngineStarted = true
      this.initEngine(true)
    })
  },
}
</script>

<style lang="less">
@import '~vux/src/styles/index.less';

html,
body {
  height: 100%;
  width: 100%;
  overflow-x: hidden;
  overflow-y: hidden;
  margin: 0px;
}

body {
  background-color: @background-color;
  // 文字均不可选
  -webkit-touch-callout: none;
  -webkit-user-select: none;
  -khtml-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
}

.header-bar {
  width: 100%;
  position: fixed !important;
  top: 0;
  z-index: 100;
}

.tabber-icon-active {
  fill: @tabber-icon-active-color;
}

.drawer {
  height: 100%;
  -webkit-user-select: text;
  -khtml-user-select: text;
  -moz-user-select: text;
  -ms-user-select: text;
  user-select: text;
  word-wrap: break-word;
  overflow-y: auto;
}

.drawer-log-title {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 10px;
  background: #fff;
  border-bottom: 1px solid #e5e7e9;
  color: #34495e;
  font-size: 13px;
  font-weight: 600;
}

.drawer-log-lines {
  padding: 8px 10px 16px;
}

.drawer-log-line {
  padding: 3px 0;
  color: #2c3e50;
  font: 11px/1.45 monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.vux-pop-out-enter-active,
.vux-pop-out-leave-active,
.vux-pop-in-enter-active,
.vux-pop-in-leave-active {
  will-change: transform;
  transition: all 500ms;
  height: 100%;
  top: 46px;
  position: absolute;
  backface-visibility: hidden;
  perspective: 1000;
}

.vux-pop-out-enter {
  opacity: 0;
  transform: translate3d(-100%, 0, 0);
}

.vux-pop-out-leave-active {
  opacity: 0;
  transform: translate3d(100%, 0, 0);
}

.vux-pop-in-enter {
  opacity: 0;
  transform: translate3d(100%, 0, 0);
}

.vux-pop-in-leave-active {
  opacity: 0;
  transform: translate3d(-100%, 0, 0);
}
</style>
