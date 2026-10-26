<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { VButton, Toast } from '@halo-dev/components'
import { axiosInstance } from '@halo-dev/api-client'
import RefreshIcon from '~icons/ri/refresh-line'
import MoreIcon from '~icons/ri/more-fill'
import ExternalIcon from '~icons/ri/external-link-line'
import StatusIcon from '~icons/ri/checkbox-blank-circle-fill'
import LogPanel from '../components/LogPanel.vue'
import {
  baseUrl,
  loadDashboard,
  phaseLabel,
  restartSucceeded,
  type BotStatus,
  type Task,
  type LogEntry,
} from '../dashboard'

const status = ref<BotStatus>()
const tasks = ref<Task[]>([])
const logs = ref<LogEntry[]>([])
const connectionError = ref('')
const busy = ref(false)
const refreshing = ref(false)
const paused = ref(false)
const loaded = ref(false)
const menuOpen = ref(false)
const clearOpen = ref(false)
const menu = ref<HTMLElement>()
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
let revision = 0

async function refresh() {
  if (refreshing.value || busy.value) return
  const requestRevision = revision
  refreshing.value = true
  try {
    const data = await loadDashboard((url) => axiosInstance.get(url))
    if (disposed || requestRevision !== revision) return
    status.value = data.status
    tasks.value = data.tasks
    logs.value = [...data.logs].reverse()
    loaded.value = true
    connectionError.value = ''
  } catch {
    if (!disposed && requestRevision === revision)
      connectionError.value = loaded.value
        ? '无法获取最新状态，请检查网络或登录权限。当前显示上次成功获取的数据。'
        : '无法读取状态，请检查网络或登录权限后重试。'
  } finally {
    refreshing.value = false
    // 快速暂停再恢复时，等旧请求结束后补发，避免被并发保护吞掉。
    if (!disposed && !paused.value && requestRevision !== revision) void refresh()
  }
}

function togglePause() {
  paused.value = !paused.value
  // 丢弃暂停前的在途响应，避免阅读时列表继续跳动。
  if (paused.value) revision++
  else refresh()
}

async function control(action: 'restart' | 'stop') {
  if (busy.value || refreshing.value) return
  menuOpen.value = false
  busy.value = true
  try {
    const { data } = await axiosInstance.post<BotStatus>(`${baseUrl}/${action}`)
    status.value = data
    if (action === 'restart' && !restartSucceeded(data))
      Toast.error(data.error || 'Bot 尚未启动，请检查配置')
    else Toast.success(action === 'restart' ? 'Bot 已启动' : 'Bot 已停止')
  } catch {
    Toast.error('操作失败，请刷新状态后检查配置与日志')
  } finally {
    busy.value = false
    await refresh()
  }
}

async function retry(name: string) {
  if (busy.value || refreshing.value) return
  busy.value = true
  try {
    await axiosInstance.post(`${baseUrl}/tasks/${encodeURIComponent(name)}/retry`)
    Toast.success('已加入重试队列')
  } catch {
    Toast.error('重试失败，请刷新后重试')
  } finally {
    busy.value = false
    await refresh()
  }
}

async function clearLogs() {
  if (busy.value || refreshing.value) return
  busy.value = true
  try {
    await axiosInstance.delete(`${baseUrl}/logs`)
    logs.value = []
    clearOpen.value = false
    Toast.success('日志已清空')
  } catch {
    Toast.error('清空日志失败')
  } finally {
    busy.value = false
  }
}

function time(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无'
}
function closeMenu(event: PointerEvent) {
  if (event.target instanceof Node && !menu.value?.contains(event.target)) menuOpen.value = false
}
onMounted(() => {
  refresh()
  timer = setInterval(() => {
    if (!paused.value) refresh()
  }, 10000)
  document.addEventListener('pointerdown', closeMenu)
})
onUnmounted(() => {
  disposed = true
  clearInterval(timer)
  document.removeEventListener('pointerdown', closeMenu)
})
</script>

<template>
  <div class="moment-page">
    <header class="page-heading">
      <div>
        <h1>Telegram 瞬间同步</h1>
        <p>查看连接、发布队列与处理结果。</p>
      </div>
      <a class="config-link" href="/console/plugins/telegram-moment?tab=bot"
        >插件配置<ExternalIcon aria-hidden="true"
      /></a>
    </header>
    <div v-if="connectionError" role="alert" class="notice notice-error">{{ connectionError }}</div>
    <section class="status-panel" aria-label="运行状态" :aria-busy="refreshing">
      <div class="status-strip">
        <div class="bot-identity">
          <span
            class="phase"
            :class="{
              healthy: status?.phase === 'RUNNING',
              unhealthy: status && ['ERROR', 'CONFIG_ERROR', 'RECONNECTING'].includes(status.phase),
            }"
          >
            <StatusIcon aria-hidden="true" />{{
              status ? phaseLabel(status.phase) : connectionError ? '状态未知' : '正在读取状态…'
            }}
          </span>
          <span v-if="status?.username" class="bot-name">@{{ status.username }}</span>
        </div>
        <dl class="status-metrics">
          <div class="metric connection-metric">
            <dt>上次连接</dt>
            <dd>{{ status ? time(status.lastPoll) : '—' }}</dd>
          </div>
          <div class="metric">
            <dt>待处理</dt>
            <dd>{{ status?.pendingCount ?? '—' }}</dd>
          </div>
          <div class="metric">
            <dt>失败</dt>
            <dd :class="{ 'failed-count': status && status.failedCount > 0 }">
              {{ status?.failedCount ?? '—' }}
            </dd>
          </div>
          <div class="metric publish-metric">
            <dt>上次发布</dt>
            <dd>{{ status ? time(status.lastPublished) : '—' }}</dd>
          </div>
        </dl>
        <div class="status-actions">
          <VButton size="sm" :loading="refreshing" :disabled="busy" @click="refresh"
            ><RefreshIcon aria-hidden="true" />刷新</VButton
          >
          <div ref="menu" class="action-menu" @keydown.esc="menuOpen = false">
            <VButton
              size="sm"
              aria-label="更多 Bot 操作"
              :aria-expanded="menuOpen"
              aria-controls="bot-actions"
              @click="menuOpen = !menuOpen"
              ><MoreIcon aria-hidden="true"
            /></VButton>
            <div v-if="menuOpen" id="bot-actions" class="menu-popover">
              <button :disabled="busy || refreshing" @click="control('restart')">
                应用配置并重启
              </button>
              <button :disabled="busy || refreshing || !status?.running" @click="control('stop')">
                停止 Bot
              </button>
            </div>
          </div>
        </div>
      </div>
      <p v-if="status?.error" role="alert" class="notice notice-error">{{ status.error }}</p>
      <div v-if="status?.configPending" class="notice notice-warning pending-config">
        <span>配置已更改，重启 Bot 后生效。</span
        ><VButton
          size="sm"
          type="primary"
          :disabled="refreshing"
          :loading="busy"
          @click="control('restart')"
          >应用配置并重启</VButton
        >
      </div>
      <p v-if="status?.phase === 'UNCONFIGURED'" class="notice">
        请先配置 Bot Token、发布者和附件存储策略。
      </p>
    </section>
    <section class="task-panel" aria-labelledby="task-title">
      <div class="section-heading">
        <h2 id="task-title">待处理与失败任务</h2>
        <span v-if="tasks.length" class="muted">{{ tasks.length }} 条</span>
      </div>
      <p v-if="!loaded" class="task-empty">
        {{ connectionError ? '任务数据暂不可用。' : '正在读取任务…' }}
      </p>
      <p v-else-if="!tasks.length" class="task-empty">暂无待处理或失败任务。</p>
      <div v-else class="task-content">
        <div class="task-scroll" tabindex="0" aria-label="任务列表">
          <table class="task-table">
            <thead>
              <tr>
                <th scope="col">任务</th>
                <th scope="col">状态</th>
                <th scope="col">尝试次数</th>
                <th scope="col">原因</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in tasks" :key="task.name">
                <td class="task-name">{{ task.name }}</td>
                <td>
                  <span :class="{ 'failed-count': task.state === 'FAILED' }">{{
                    task.state === 'FAILED' ? '失败' : '等待处理'
                  }}</span>
                </td>
                <td>{{ task.attempts }}</td>
                <td class="task-error">{{ task.error || '—' }}</td>
                <td>
                  <VButton
                    v-if="task.state === 'FAILED'"
                    size="sm"
                    :disabled="busy || refreshing || !status?.running"
                    @click="retry(task.name)"
                    >重试</VButton
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="muted">最多显示 100 条。失败任务会保留，修复原因后可手动重试。</p>
      </div>
    </section>
    <LogPanel
      :entries="logs"
      :enabled="status?.logEnabled"
      :loaded="loaded"
      :load-error="!!connectionError"
      :paused="paused"
      :busy="busy || refreshing"
      @toggle-pause="togglePause"
      @clear="clearOpen = true"
    />
    <div v-if="clearOpen" class="clear-confirm" role="alert" aria-label="确认清空日志">
      <div>
        <strong>清空当前日志？</strong>
        <p>已缓存的日志将被删除，不影响 Bot 同步和任务队列。</p>
      </div>
      <div class="confirm-actions">
        <VButton size="sm" :disabled="busy" @click="clearOpen = false">取消</VButton
        ><VButton size="sm" type="danger" :disabled="refreshing" :loading="busy" @click="clearLogs"
          >确认清空</VButton
        >
      </div>
    </div>
  </div>
</template>

<style scoped>
.moment-page {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  color: #1f2937;
  font:
    14px/1.5 -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'Microsoft YaHei',
    sans-serif;
}
.moment-page :deep(.btn-content) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}
.moment-page :deep(button:has(.btn-content)) {
  min-height: 36px;
  font-size: 14px;
}
.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.3;
  font-weight: 650;
  color: #111827;
}
.page-heading p {
  margin: 6px 0 0;
  color: #64748b;
}
.config-link {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: #2563eb;
  white-space: nowrap;
  padding-top: 5px;
}
.config-link:hover {
  text-decoration: underline;
}
.status-panel,
.task-panel {
  background: white;
  border: 1px solid #e8edf2;
  border-radius: 6px;
}
.status-strip {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px 20px;
  flex-wrap: wrap;
}
.bot-identity {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  min-width: 0;
}
.phase {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #64748b;
  white-space: nowrap;
}
.phase svg {
  width: 16px;
  height: 16px;
}
.healthy {
  color: #059669;
}
.unhealthy {
  color: #b45309;
}
.bot-name {
  color: #64748b;
  overflow-wrap: anywhere;
}
.status-metrics {
  display: flex;
  flex: 1;
  gap: 0;
  margin: 0;
}
.metric {
  padding: 0 24px;
  border-left: 1px solid #edf0f4;
  flex: 1;
  min-width: 80px;
}
dt {
  color: #64748b;
  font-size: 13px;
  white-space: nowrap;
}
dd {
  margin: 4px 0 0;
  font-size: 14px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.status-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.action-menu {
  position: relative;
}
.menu-popover {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  z-index: 10;
  min-width: 180px;
  padding: 5px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: white;
  box-shadow: 0 6px 20px #0f172a14;
}
.menu-popover button {
  padding: 10px 12px;
  width: 100%;
  text-align: left;
  border-radius: 4px;
  background: none;
  border: 0;
  font: inherit;
  cursor: pointer;
}
.menu-popover button:hover {
  background: #f1f5f9;
}
.menu-popover button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.notice {
  margin: 0;
  padding: 12px 20px;
  background: #f8fafc;
  color: #475569;
  overflow-wrap: anywhere;
  border-radius: 5px;
}
.notice-error {
  background: #fff1f2;
  color: #b91c1c;
}
.notice-warning {
  background: #fffbeb;
  color: #92400e;
}
.pending-config {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #edf0f4;
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}
.task-empty {
  margin: 0;
  padding: 18px 20px;
  color: #64748b;
}
.task-content {
  padding: 0 20px 14px;
}
.task-scroll {
  overflow: auto;
  max-height: 320px;
}
.task-table {
  min-width: 680px;
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
.task-table th,
.task-table td {
  padding: 12px 8px;
  vertical-align: top;
  border-bottom: 1px solid #edf0f4;
}
.task-table th {
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
}
.task-table td:not(.task-name):not(.task-error) {
  white-space: nowrap;
}
.task-name {
  max-width: 260px;
  overflow-wrap: anywhere;
  font-family: ui-monospace, Consolas, monospace;
}
.task-error {
  min-width: 180px;
  overflow-wrap: anywhere;
}
.failed-count {
  color: #b91c1c;
  font-weight: 600;
}
.muted {
  color: #64748b;
  font-size: 13px;
}
.task-content p {
  margin: 12px 0 0;
}
.clear-confirm {
  position: sticky;
  bottom: 16px;
  background: white;
  border: 1px solid #fecaca;
  box-shadow: 0 4px 20px #0f172a14;
  border-radius: 6px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  z-index: 5;
}
.clear-confirm p {
  margin: 4px 0 0;
  color: #64748b;
}
.confirm-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
@media (max-width: 1200px) {
  .status-metrics {
    order: 3;
    flex-basis: 100%;
  }
  .metric:first-child {
    padding-left: 0;
    border-left: 0;
  }
}
@media (max-width: 640px) {
  .moment-page {
    padding: 16px;
    gap: 16px;
  }
  h1 {
    font-size: 21px;
  }
  .page-heading {
    flex-wrap: wrap;
    gap: 8px;
  }
  .status-strip {
    padding: 18px 16px;
    gap: 16px;
  }
  .bot-identity {
    gap: 8px;
    flex-direction: column;
    align-items: flex-start;
    max-width: calc(100% - 108px);
  }
  .status-metrics {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
  }
  .metric {
    padding: 0;
    border: 0;
    min-width: 0;
  }
  .connection-metric {
    grid-column: 1 / -1;
  }
  .publish-metric {
    grid-column: 1 / -1;
  }
  .section-heading {
    padding: 14px 16px;
  }
  .task-empty {
    padding: 16px;
  }
  .clear-confirm {
    flex-wrap: wrap;
  }
  .confirm-actions {
    margin-left: auto;
  }
}
</style>
