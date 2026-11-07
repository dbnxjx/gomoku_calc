<template>
  <div class="game" :class="{ 'game-landscape': landscapeLayout }">
    <div class="board-box">
      <Board ref="board" :is-ai-turn="isAITurn" :kill-point-scores="killPointScores" @clicked="clicked"></Board>

      <div class="button-box" :style="{ width: `${buttonBarWidth}px` }">
        <flexbox :gutter="0">
          <flexbox-item>
            <x-button @click.native="newGame" style="padding: 0">
              <i class="fa fa-file-o" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button :disabled="!canTreeBackward" style="padding: 0" @click.native="() => {
              checkThinking().then(() => {
                backToBeginWithTree()
              })
            }
              ">
              <i class="fa fa-angle-double-left fa-lg" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button :disabled="!canTreeBackward" style="padding: 0" @click.native="() => {
              checkThinking().then(() => {
                backwardWithTree()
              })
            }
              ">
              <i class="fa fa-angle-left fa-lg" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button :disabled="!canTreeForward" style="padding: 0" @click.native="() => {
              checkThinking().then(() => {
                forwardWithTree()
              })
            }
              ">
              <i class="fa fa-angle-right fa-lg" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button :disabled="!canTreeForward" style="padding: 0" @click.native="() => {
              checkThinking().then(() => {
                forwardToEndWithTree()
              })
            }
              ">
              <i class="fa fa-angle-double-right fa-lg" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button v-if="!ready" style="padding: 0" @click.native="showLoading = true">
              <i class="fa fa-spinner fa-spin"></i>
            </x-button>
            <x-button v-else :disabled="thinking || gameEnded" style="padding: 0" @click.native="startThink">
              <i v-if="thinking" class="fa fa-cog fa-spin"></i>
              <i v-else class="fa fa-play" :style="{
                color: gameEnded ? '#145A32' : '#229954',
              }"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button :disabled="!thinking" style="padding: 0" @click.native="stop">
              <i class="fa fa-stop" :style="{ color: thinking ? '#E74C3C' : '#78281F' }"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button style="padding: 0" aria-label="拍照识别" @click.native="showSourceSheet = true">
              <i class="fa fa-camera" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
          <flexbox-item>
            <x-button style="padding: 0" @click.native="setValue({ key: 'showIndex', value: !showIndex })">
              <i v-if="showIndex" class="fa fa-info-circle" aria-hidden="true"></i>
              <i v-else class="fa fa-info" aria-hidden="true"></i>
            </x-button>
          </flexbox-item>
        </flexbox>
      </div>
    </div>

    <load-more :show-loading="false" background-color="#fbf9fe" class="seperator"></load-more>

    <div class="info-box">
      <div v-show="showAnalysis">
        <x-progress v-if="engineType === 'RAPFI' && thinking && aiTimeUsed > 0 && turnTime > 0"
          :percent="Math.min((100 * aiTimeUsed) / Math.min(turnTime, matchTime), 100)"
          :show-cancel="false"></x-progress>

        <div class="analysis-summary">
          <span v-if="engineType === 'KATAGO'">总 visits {{ getNodesText(outputs.nodes) }}</span>
          <span v-else>总节点 {{ getNodesText(outputs.nodes) }}</span>
          <span>{{ getSpeedText(outputs.speed || outputs.visitsPerSecond) }} visits/s</span>
          <span>{{ getTimeText(outputs.time) }}</span>
        </div>

        <template v-if="engineType === 'RAPFI'">
          <x-table :cell-bordered="true" style="background-color: #fff; line-height: 210%; table-layout: fixed">
            <thead>
              <tr style="background-color: #f7f7f7">
                <th style="width: 44px">{{ $t('game.info.nbestIndex') }}</th>
                <th style="width: 62px">{{ $t('game.info.depth') }}</th>
                <th style="width: 62px">{{ $t('game.info.eval') }}</th>
                <th>{{ $t('game.info.bestline') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="i in Math.min(nbest, outputs.pv.length)" :key="i">
                <td>{{ i }}</td>
                <td>
                  {{ outputs.pv[i - 1].depth + '-' + outputs.pv[i - 1].seldepth }}
                </td>
                <td style="font-weight: bold">{{ outputs.pv[i - 1].eval }}</td>
                <td>
                  <div class="bestline-scroll">{{ formatBestline(outputs.pv[i - 1].bestline) }}</div>
                </td>
              </tr>
            </tbody>
          </x-table>
        </template>

        <div v-else class="katago-analysis">
          <x-table :cell-bordered="true" style="background-color: #fff; line-height: 210%">
            <thead>
              <tr style="background-color: #f7f7f7">
                <th>选点</th>
                <th>位置</th>
                <th>胜率</th>
                <th>visits</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="candidate in kataGoCandidates" :key="candidate.rank">
                <td>{{ candidate.rank }}</td>
                <td>{{ candidate.move }}</td>
                <td>{{ candidate.winrate }}</td>
                <td>{{ getNodesText(candidate.visits) }}</td>
              </tr>
              <tr v-if="kataGoCandidates.length === 0">
                <td colspan="4">等待 KataGo 分析输出</td>
              </tr>
            </tbody>
          </x-table>
        </div>
      </div>

      <!-- v-show keeps the record loading and auto-save running while the tree is hidden. -->
      <MoveTree v-show="showMoveTree" ref="moveTree" @node-selected="onTreeNodeSelected" @tree-cleared="onTreeCleared" @record-loaded="onRecordLoaded" />
    </div>

    <div v-transfer-dom>
      <x-dialog v-model="showLoading" hide-on-blur>
        <div style="margin:20px auto;">{{ $t('game.engineLoading') }}</div>
        <x-circle style="width: 100px;height: 100px;margin: 20px auto;" :percent="loadingProgress * 100"
          :stroke-width="6" :trail-width="6">
          <span style="color:#36D1DC">{{ Math.floor(loadingProgress * 10000) / 100 }}%</span>
        </x-circle>
        <div style="margin:10px auto;" @click="showLoading = false">
          <span class="vux-close"></span>
        </div>
      </x-dialog>
    </div>

    <div v-transfer-dom>
      <RecognizeDialog v-if="recognition" ref="recognizeDialog" :initial="recognition" :retake-label="recognitionSource === 'gallery' ? '重选' : '重拍'"
        @close="closeRecognition(true)" @retake="openRecognition(recognitionSource)" @confirm="applyRecognizedBoard" />
      <div v-if="recognizing" class="recognizing-mask">正在识别…</div>
      <div v-if="showSourceSheet" class="source-mask" @click="showSourceSheet = false">
        <div class="source-sheet" @click.stop>
          <div class="source-item" @click="openRecognition('camera')">拍照</div>
          <div class="source-item" @click="openRecognition('gallery')">相册</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {
  TransferDom,
  Flexbox,
  FlexboxItem,
  XButton,
  LoadMore,
  XTable,
  XProgress,
  XDialog,
  XCircle,
} from 'vux'
import { debounce } from 'throttle-debounce'
import Board from '@/components/Board.vue'
import MoveTree from '@/components/MoveTree.vue'
import RecognizeDialog from '@/components/RecognizeDialog.vue'
import { capture, interceptBack, onBackPressed } from '@/services/boardRecognition'
import { checkCounts } from '@/services/boardView'
import { mapState, mapGetters, mapMutations, mapActions } from 'vuex'
import { Promise } from 'q'
import { RENJU } from '@/store/modules/settings'
import { isSamePosition } from '@/store/modules/ai'
import recordService from '@/services/recordService'

export default {
  name: 'game',
  directives: {
    TransferDom,
  },
  components: {
    Board,
    MoveTree,
    RecognizeDialog,
    Flexbox,
    FlexboxItem,
    XButton,
    LoadMore,
    XTable,
    XProgress,
    XDialog,
    XCircle,
  },
  data: function () {
    return {
      aiTimeUsed: 0,
      showLoading: false,
      console: console,
      thinkingCanceled: false,
      recognition: null,
      recognizing: false,
      recognitionSource: 'camera',
      showSourceSheet: false,
      resumeAfterRecognition: false,
      boardSizeFromRecord: false,
      debouncedQueryKillPoints: debounce(120, () => this.$store.dispatch('ai/queryKillPoints')),
    }
  },
  computed: {
    ...mapState(['screenWidth', 'screenHeight']),
    ...mapState('settings', [
      'boardSize',
      'rule',
      'nbest',
      'kataGoMaxPoints',
      'aiThinkBlack',
      'aiThinkWhite',
      'showAnalysis',
      'showMoveTree',
      'showIndex',
      'showKillPoints',
      'engineType',
    ]),
    ...mapState('ai', ['outputs', 'thinking', 'lastThinkTime', 'ready', 'loadingProgress', 'nativeEngineAvailable', 'kataGoPaused', 'killPointsPosition', 'databaseRevision']),
    ...mapState('position', ['position', 'lastPosition', 'winline', 'swaped']),
    ...mapGetters(['landscapeLayout']),
    ...mapGetters('settings', ['turnTime', 'matchTime', 'gameRule']),
    ...mapGetters('ai', ['bestlineStr', 'bestline']),
    ...mapGetters('position', ['isEmpty', 'playerToMove', 'moveLeftCount']),
    killPointScores() {
      if (this.engineType !== 'RAPFI' || this.thinking ||
          !isSamePosition(this.killPointsPosition, this.position)) return []
      return this.outputs.killPoints || []
    },
    // Everything that can change which database mates apply to the shown position.
    killPointQueryKey() {
      return [
        this.engineType,
        this.showKillPoints,
        this.ready,
        this.thinking,
        this.rule,
        this.boardSize,
        this.databaseRevision,
        this.position.join(';'),
      ].join('|')
    },
    kataGoCandidates() {
      const analyzedPosition = this.$store.state.ai.lastThinkPosition
      if (analyzedPosition.length !== this.position.length || analyzedPosition.some((move, index) =>
        move[0] !== this.position[index][0] || move[1] !== this.position[index][1]
      )) return []
      const columns = 'ABCDEFGHJKLMNOPQRSTUVWXYZ'
      return (this.outputs.heatmap || [])
        .slice()
        .sort((a, b) => (Number(a[4]) || 0) - (Number(b[4]) || 0))
        .slice(0, Math.max(1, Number(this.kataGoMaxPoints) || 10))
        .map((point, index) => ({
          rank: Number(point[4]) || index + 1,
          move: `${columns[Number(point[0])] || '?'}${this.boardSize - Number(point[1])}`,
          winrate: `${(Math.max(0, Math.min(1, Number(point[2]) || 0)) * 100).toFixed(1)}%`,
          visits: Number(point[3]) || 0,
        }))
    },
    buttonBarWidth() {
      // All buttons stay visible: full size when there is room, otherwise fit the board column.
      const FullWidth = 413
      return Math.min(FullWidth, this.landscapeLayout ? Math.floor(this.screenWidth / 2) : this.screenWidth)
    },
    bestline() {
      return this.bestlineStr(0)
    },
    isAITurn() {
      return (
        (this.playerToMove == 'BLACK' && this.aiThinkBlack) ||
        (this.playerToMove == 'WHITE' && this.aiThinkWhite)
      )
    },
    gameEnded() {
      return this.winline.length > 0 || this.moveLeftCount == 0
    },
    // 这些界面打开时，系统返回键退出它们，而不是退出应用
    interceptsBack() {
      return this.showSourceSheet || this.recognizing || !!this.recognition
    },
    currentTreeNode() {
      const treeVersion = this.$store.state.moveTree.version
      if (treeVersion < 0) return null
      return this.$store.getters['moveTree/currentNode']
    },
    canTreeBackward() {
      return !!(this.currentTreeNode && this.currentTreeNode.parent)
    },
    canTreeForward() {
      return !!this.getNextTreeChild(this.currentTreeNode)
    },
  },
  methods: {
    ...mapMutations('position', {
      newBoard: 'new',
      setSwaped: 'setSwaped',
    }),
    ...mapMutations('settings', ['setValue']),
    ...mapMutations('ai', ['clearUsedTime']),
    ...mapActions('position', [
      'makeMove',
      'backToBegin',
      'backward',
      'forward',
      'forwardToEnd',
      'rotate',
      'flip',
      'moveTowards',
    ]),
    ...mapActions('ai', ['think', 'stop', 'restart']),

    checkThinking() {
      return new Promise((resolve) => {
        if (this.engineType === 'KATAGO') {
          resolve()
          return
        }
        if (this.thinking) {
          this.$vux.confirm.show_i18n({
            title: this.$t('game.interruptThinking.title'),
            content: this.$t('game.interruptThinking.msg'),
            onCancel: () => { },
            onConfirm: () => {
              this.thinkingCanceled = true
              this.stop({ manual: false })
              resolve()
            },
          })
        } else resolve()
      })
    },

    prepareKataGoForPositionChange() {
      if (this.engineType !== 'KATAGO') return false
      const shouldResume = this.thinking && !this.kataGoPaused
      if (this.thinking) this.stop({ manual: false })
      else this.$store.dispatch('ai/invalidateAnalysis')
      return shouldResume
    },

    onFlipOption(key) {
      const shouldResume = this.prepareKataGoForPositionChange()
      switch (key) {
        case 0:
          this.flip([1, 0])
          break
        case 1:
          this.flip([0, 1])
          break
        case 2:
          this.flip([0, 0])
          break
        case 3:
          this.flip([1, 1])
          break
      }
      if (shouldResume) this.startThink()
    },

    onMoveOption(key) {
      const shouldResume = this.prepareKataGoForPositionChange()
      switch (key) {
        case 0:
          this.moveTowards([0, -1])
          break
        case 1:
          this.moveTowards([0, 1])
          break
        case 2:
          this.moveTowards([-1, 0])
          break
        case 3:
          this.moveTowards([1, 0])
          break
      }
      if (shouldResume) this.startThink()
    },

    rotateBoard() {
      const shouldResume = this.prepareKataGoForPositionChange()
      this.rotate()
      if (shouldResume) this.startThink()
    },

    newGame() {
      this.checkThinking().then(async () => {
        const defaultName = recordService.formatDateName()
        const recordName = prompt('新建棋谱名称', defaultName)
        if (recordName === null) return
        const newRecordName = (recordName || '').trim() || defaultName
        if (this.$refs.moveTree && await this.$refs.moveTree.hasRecordName(newRecordName)) {
          alert('已有同名棋谱')
          return
        }
        const shouldResume = this.prepareKataGoForPositionChange()
        this.newBoard(this.boardSize)
        this.restart()
        if (this.$refs.moveTree) await this.$refs.moveTree.createRecord(newRecordName)
        if (shouldResume) this.startThink()
      })
    },

    onSystemBack() {
      if (this.recognizing) return
      if (this.showSourceSheet) this.showSourceSheet = false
      else if (this.$refs.recognizeDialog) this.$refs.recognizeDialog.handleBack()
    },

    // 路数由棋谱决定时用这个，不走 boardSize 监听里的“换路数就开新棋谱”
    applyBoardSize(size, alreadyWarned = false) {
      if (size > 15 && this.engineType === 'KATAGO') {
        this.setValue({ key: 'engineType', value: 'RAPFI' })
        if (!alreadyWarned) this.$vux.alert.show_i18n({ title: '已切换到 Rapfi', content: `这份棋谱是 ${size} 路，KataGo 只支持 15 路` })
      }
      if (this.boardSize === size) return
      this.boardSizeFromRecord = true
      this.setValue({ key: 'boardSize', value: size })
      this.restart() // 上一个路数的分析结果不能留在这个棋盘上
    },

    openRecognition(source) {
      this.showSourceSheet = false
      this.recognitionSource = source
      this.checkThinking().then(async () => {
        // 取景要占用 CPU，KataGo 的后台分析先停下，没用这张图时再恢复
        if (this.engineType === 'KATAGO' && this.thinking) {
          this.resumeAfterRecognition = !this.kataGoPaused
          this.stop({ manual: false })
        }
        try {
          const pending = capture(source)
          this.recognizing = true
          const result = await pending
          if (result.cancelled) {
            // 重拍时取消，还回到上一次的结果
            if (!this.recognition) this.closeRecognition(true)
            return
          }
          this.recognition = result
        } catch (error) {
          this.$vux.alert.show_i18n({ title: '识别失败', content: String(error.message || error) })
          if (!this.recognition) this.closeRecognition(true)
        } finally {
          this.recognizing = false
        }
      })
    },

    closeRecognition(resume) {
      this.recognition = null
      const shouldResume = resume && this.resumeAfterRecognition
      this.resumeAfterRecognition = false
      if (shouldResume && this.ready && !this.gameEnded) this.startThink()
    },

    async uniqueRecordName() {
      const base = recordService.formatDateName()
      let name = base
      for (let suffix = 2; this.$refs.moveTree && await this.$refs.moveTree.hasRecordName(name); suffix++) {
        name = `${base}-${suffix}`
      }
      return name
    },

    // 确认的局面：按时间新建棋谱，局面作为落子树的根，之后的落子从第 1 手算起
    async applyRecognizedBoard({ black, white, size }) {
      const check = checkCounts(black.length, white.length)
      const stones = check.valid ? recordService.orderSetup(black, white) : null
      if (!stones) return
      const shouldResume = this.resumeAfterRecognition && size <= 15
      this.closeRecognition(false)
      this.applyBoardSize(size, true) // 结果页已经提示过引擎切换
      await this.$nextTick()
      const name = await this.uniqueRecordName()
      this.newBoard(size)
      this.restart()
      if (this.$refs.moveTree) await this.$refs.moveTree.createRecord(name, stones)
      this.$store.dispatch('position/loadSetup', stones)
      if (shouldResume && this.ready && !this.gameEnded) this.startThink()
    },

    getVisibleTreeChildren(node) {
      return node && node.children ? node.children : []
    },

    getNextTreeChild(node) {
      const children = this.getVisibleTreeChildren(node)
      return children.length > 0 ? children[0] : null
    },

    // 回到落子树的根：空棋盘，或者棋谱记录的局面
    resetBoardToRoot() {
      this.newBoard(this.boardSize)
      this.$store.dispatch('position/loadSetup', this.$store.state.moveTree.setup)
    },

    syncBoardToTreeNode(node) {
      if (!node) return
      const shouldResume = this.prepareKataGoForPositionChange()
      const path = []
      let n = node
      while (n) {
        if (n.x >= 0 && n.y >= 0) path.unshift([n.x, n.y])
        n = n.parent
      }
      this.resetBoardToRoot()
      for (const pos of path) {
        if (!this.makeMove(pos)) break
      }
      this.$store.dispatch('moveTree/goToNode', node.id)
      if (shouldResume) this.startThink()
    },

    backwardWithTree() {
      if (!this.canTreeBackward) return
      this.syncBoardToTreeNode(this.currentTreeNode.parent)
      this.clearUsedTime()
    },

    backToBeginWithTree() {
      const rootNode = this.$store.getters['moveTree/rootNode']
      this.syncBoardToTreeNode(rootNode)
      this.clearUsedTime()
    },

    forwardWithTree() {
      const child = this.getNextTreeChild(this.currentTreeNode)
      if (!child) return
      this.syncBoardToTreeNode(child)
    },

    forwardToEndWithTree() {
      let node = this.currentTreeNode
      let child = this.getNextTreeChild(node)
      while (child) {
        node = child
        child = this.getNextTreeChild(node)
      }
      if (node && node !== this.currentTreeNode) this.syncBoardToTreeNode(node)
    },

    clicked(e) {
      let pos = [e.x, e.y]
      const isKataGo = this.engineType === 'KATAGO'
      if (this.thinking) {
        if (!isKataGo || e.button !== 0) return
        this.stop({ manual: false })
      }

      if (e.button == 0) {
        // 检查是否点击了棋盘上的棋子（任意棋子都可以触发AI推算）
        if (!this.isAITurn && !this.isEmpty(pos) && this.winline.length == 0) {
          // 点击了已下的棋子,触发AI推算
          this.startThink()
          return
        }

        if ((this.isEmpty(pos) && this.winline.length == 0) || this.isAITurn) {
          if (!this.isAITurn) {
            if (this.rule == 5 && !this.swaped && this.position.length == 1) {
              // SWAP1规则
              return this.$vux.confirm.show_i18n({
                title: this.$t('game.swap.questionTitle'),
                content: this.$t('game.swap.questionMsg'),
                onCancel: () => {
                  this.setSwaped()
                },
                onConfirm: () => {
                  this.swapBlackAndWhite()
                  if (this.isAITurn) this.startThink()
                },
              })
            } else if (this.engineType === 'RAPFI' && this.gameRule === RENJU && this.playerToMove === 'BLACK') {
              let isForbidPos = false
              for (let forbidPos of this.outputs.forbid) {
                if (pos[0] == forbidPos[0] && pos[1] == forbidPos[1]) {
                  isForbidPos = true
                  break
                }
              }
              if (isForbidPos) {
                return this.$vux.alert.show_i18n({
                  title: this.$t('game.forbid.title'),
                  content: this.$t('game.forbid.msg'),
                })
              }
            }

            const color = this.playerToMove === 'BLACK' ? 'black' : 'white'
            this.makeMove(pos)
            this.$store.dispatch('moveTree/recordMove', { x: pos[0], y: pos[1], color })
            if (isKataGo && !this.kataGoPaused && this.ready && !this.gameEnded) this.startThink()
          }
          if (this.isAITurn) this.startThink()
        }
      } else if (e.button == 2) {
        this.backwardWithTree()
      }
    },

    startThink() {
      if (this.gameEnded) return

      if (this.engineType === 'KATAGO') {
        this.think()
        return
      }

      this.think().then((pos) => {
        if (this.thinkingCanceled) {
          this.thinkingCanceled = false
          return
        }

        if (this.outputs.swap) {
          this.swapBlackAndWhite()
          this.$vux.alert.show_i18n({
            title: this.$t('game.swap.title'),
            content: this.$t('game.swap.msg'),
            onHide() {
              if (this.isAITurn) this.startThink()
            },
          })
        } else {
          const color = this.playerToMove === 'BLACK' ? 'black' : 'white'
          this.makeMove(pos)
          this.$store.dispatch('moveTree/recordMove', { x: pos[0], y: pos[1], color })

          if (this.isAITurn) this.startThink()
        }
      })
    },

    startThinkBalance(mode) {
      if (this.gameEnded) return

      this.think({ balanceMode: mode }).then((pos) => {
        if (this.thinkingCanceled) {
          this.thinkingCanceled = false
          return
        }

        const color1 = this.playerToMove === 'BLACK' ? 'black' : 'white'
        this.makeMove(pos)
        this.$store.dispatch('moveTree/recordMove', { x: pos[0], y: pos[1], color: color1 })
        if (mode == 2) {
          const pos2 = this.outputs.pv[0].bestline[1]
          const color2 = this.playerToMove === 'BLACK' ? 'black' : 'white'
          this.makeMove(pos2)
          this.$store.dispatch('moveTree/recordMove', { x: pos2[0], y: pos2[1], color: color2 })
        }
      })
    },

    swapBlackAndWhite() {
      this.setSwaped()
      let thinkBlack = this.aiThinkWhite
      let thinkWhite = this.aiThinkBlack
      this.setValue({ key: 'aiThinkBlack', value: thinkBlack })
      this.setValue({ key: 'aiThinkWhite', value: thinkWhite })
    },

    getSpeedText(speed) {
      if (speed < 10000) {
        return speed.toString();
      } else if (speed < 100000000) {
        return Math.floor(speed / 1000) + "K";
      } else {
        return Math.floor(speed / 1000000) + "M";
      }
    },

    formatBestline(bestline) {
      return (bestline || []).map((pos) =>
        `${String.fromCharCode('A'.charCodeAt(0) + pos[0])}${this.boardSize - pos[1]}`
      ).join(' ')
    },

    getNodesText(nodes) {
      if (nodes < 10000) {
        return nodes.toString();
      } else if (nodes < 10000000) {
        return Math.floor(nodes / 1000) + "K";
      } else if (nodes < 100000000000) {
        return Math.floor(nodes / 1000000) + "M";
      } else if (nodes < 100000000000000) {
        return Math.floor(nodes / 1000000000) + "G";
      } else {
        return Math.floor(nodes / 1000000000000) + "T";
      }
    },

    getTimeText(time) {
      if (time < 1000000) {
        return Math.floor(time / 1000) + "." + (Math.floor(time / 100) % 10) + "s";
      } else if (time < 360000000) {
        return Math.floor(time / 60000) + "min";
      } else {
        return Math.floor(time / 3600000) + "h";
      }
    },

    onTreeNodeSelected(node) {
      if (!node) return
      this.syncBoardToTreeNode(node)
    },

    onTreeCleared() {
      const shouldResume = this.prepareKataGoForPositionChange()
      this.newBoard(this.boardSize)
      this.restart()
      if (shouldResume) this.startThink()
    },

    onRecordLoaded(size) {
      if (size) this.applyBoardSize(size)
      const shouldResume = this.prepareKataGoForPositionChange()
      // 棋谱加载完成后，恢复棋盘状态
      const path = this.$store.getters['moveTree/getPathToCurrent']
      this.resetBoardToRoot()
      if (path && path.length > 0) {
        for (const pos of path) this.makeMove(pos)
      }
      if (shouldResume) this.startThink()
    },
  },
  watch: {
    bestline: function () {
      if (this.$refs.bestlineArea) this.$refs.bestlineArea.updateAutosize()
    },
    boardSize: async function (newSize) {
      if (this.boardSizeFromRecord) {
        this.boardSizeFromRecord = false
        return
      }
      if (this.thinking) this.stop()
      this.newBoard(newSize)
      this.restart()
      // 当前棋谱有内容时另开一份，否则换路数会把它存成空棋谱
      const tree = this.$store.state.moveTree
      const hasContent = tree.setup.length > 0 || (tree.rootNode && tree.rootNode.children.length > 0)
      if (hasContent && this.$refs.moveTree) await this.$refs.moveTree.createRecord(await this.uniqueRecordName())
      else this.$store.dispatch('moveTree/init')
    },
    interceptsBack: function (active) {
      interceptBack(active).catch(() => {})
    },
    loadingProgress: function (progress) {
      if (progress == 1) this.showLoading = false
    },
    killPointQueryKey: function () {
      this.debouncedQueryKillPoints()
    },
  },
  mounted() {
    this.newBoard(this.boardSize)
    Promise.resolve(onBackPressed(() => this.onSystemBack())).catch(() => {})

    window.addEventListener('keydown', (event) => {
      const target = event.target

      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
        return
      if (event.repeat)
        return

        switch (event.key) {
        case 'ArrowLeft':
          this.checkThinking().then(() => this.backwardWithTree())
          break
        case 'ArrowRight':
          this.checkThinking().then(() => this.forwardWithTree())
          break
        case 'Home':
          this.checkThinking().then(() => this.backToBeginWithTree())
          break
        case 'End':
          this.checkThinking().then(() => this.forwardToEndWithTree())
          break
        case ' ':
          if (this.thinking) this.stop()
          else if (this.ready && !this.gameEnded) this.startThink()
          break
        case 'b':
          this.checkThinking().then(() => this.startThinkBalance(1))
          break
        case 'B':
          this.checkThinking().then(() => this.startThinkBalance(2))
          break
      }
    })
    setInterval(() => {
      this.aiTimeUsed = this.thinking ? Date.now() - this.lastThinkTime : 0
    }, 100)
  },
}
</script>

<style lang="less" scoped>
.source-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1002;
  background: rgba(0, 0, 0, 0.5);
}

.source-sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
}

.source-item {
  padding: 15px;
  font-size: 16px;
  color: #333;
  text-align: center;
  border-top: 1px solid #eee;
}

.recognizing-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 1001;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  background: rgba(0, 0, 0, 0.6);
}

.katago-summary {
  padding: 8px 10px;
  color: #34495e;
  font-size: 13px;
  background: #fff;
  border-bottom: 1px solid #e5e7e9;
}

.analysis-summary {
  display: flex;
  justify-content: space-around;
  padding: 8px 10px;
  color: #34495e;
  font-size: 13px;
  background: #fff;
  border-bottom: 1px solid #e5e7e9;
}

.selectable {
  -webkit-user-select: text;
  -khtml-user-select: text;
  -moz-user-select: text;
  -ms-user-select: text;
  user-select: text;
}

.game {
  overflow-x: hidden;
}

.button-box {
  margin: auto;
  margin-top: ~'min(1vw, 10px)';
}

.seperator {
  margin-top: 20px;
  margin-bottom: 0px;
}

.bestline-scroll {
  overflow-x: auto;
  white-space: nowrap;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-x pan-y;
}

// Board on the left half, analysis and move tree on the right half. The halves
// are fixed so that hiding the right side never resizes the board, and the whole
// page scrolls when the board is taller than the screen.
.game-landscape {
  display: flex;
  flex-wrap: nowrap;
  align-items: flex-start;

  .seperator {
    display: none !important;
  }

  .board-box,
  .info-box {
    flex: 0 0 50%;
    width: 50%;
    min-width: 0;
    box-sizing: border-box;
  }

  .board-box {
    padding-top: 6px;
    padding-bottom: 10px;
  }

  .info-box {
    padding: 6px 10px 10px;
  }
}

.loading-circle {
  width: 100px;
  height: 100px;
  margin: 20px auto;
}

.icon-button {
  background: none;
  border: none;
  padding: 0.1rem 0.25rem;
  border-radius: 0.1rem;
  transition: background-color 0.2s ease;
}

.icon-button:active {
  background-color: rgba(0, 0, 0, 0.2);
}

.icon-button i {
  font-size: 0.8rem;
  color: darkgray;
}

.icon-button:hover i {
  color: black;
}

.icon-button:active i {
  color: black;
}
</style>
