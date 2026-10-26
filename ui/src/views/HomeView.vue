<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { VButton, VCard, VTag, Toast } from '@halo-dev/components'
import { axiosInstance } from '@halo-dev/api-client'
import { baseUrl, loadDashboard, phaseLabel, restartSucceeded, type BotStatus, type Task, type LogEntry } from '../dashboard'

const status = ref<BotStatus>()
const tasks = ref<Task[]>([])
const logs = ref<LogEntry[]>([])
const connectionError = ref('')
const busy = ref(false)
const refreshing = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false

async function refresh() {
  if (refreshing.value || busy.value) return
  refreshing.value = true
  try {
    const data = await loadDashboard((url) => axiosInstance.get(url))
    if (disposed) return
    status.value = data.status
    tasks.value = data.tasks
    logs.value = [...data.logs].reverse()
    connectionError.value = ''
  } catch {
    if (!disposed) connectionError.value = '无法获取最新状态，请检查网络或登录权限。下方保留上次成功获取的数据。'
  } finally { refreshing.value = false }
}

async function control(action: 'restart' | 'stop') {
  busy.value = true
  try {
    const { data } = await axiosInstance.post<BotStatus>(`${baseUrl}/${action}`)
    status.value = data
    if (action === 'restart' && !restartSucceeded(data)) Toast.error(data.error || 'Bot 尚未启动，请检查配置')
    else Toast.success(action === 'restart' ? 'Bot 已启动' : 'Bot 已停止')
  } catch { Toast.error('操作失败，请刷新状态后检查配置与日志') }
  finally { busy.value = false; await refresh() }
}

async function retry(name: string) {
  busy.value = true
  try {
    await axiosInstance.post(`${baseUrl}/tasks/${encodeURIComponent(name)}/retry`)
    Toast.success('已加入重试队列')
  } catch { Toast.error('重试失败，请刷新后重试') }
  finally { busy.value = false; await refresh() }
}

async function clearLogs() {
  try { await axiosInstance.delete(`${baseUrl}/logs`); logs.value = [] }
  catch { Toast.error('清空日志失败') }
}

function time(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '暂无' }
onMounted(() => { refresh(); timer = setInterval(refresh, 10000) })
onUnmounted(() => { disposed = true; clearInterval(timer) })
</script>

<template>
  <div class="p-4 flex flex-col gap-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div><h1 class="text-xl font-semibold">Telegram 瞬间同步</h1><p class="text-sm text-gray-500">查看连接、发布队列与处理结果，每 10 秒自动刷新。</p></div>
      <a class="text-sm text-blue-600 underline" href="/console/plugins/telegram-moment?tab=bot">打开插件配置</a>
    </div>
    <p v-if="connectionError" role="alert" class="rounded bg-red-50 p-3 text-red-700">{{ connectionError }}</p>
    <VCard title="运行状态">
      <div class="p-4 flex flex-col gap-3">
        <div class="flex flex-wrap items-center gap-3">
          <VTag v-if="status" :type="status.phase === 'RUNNING' ? 'success' : 'default'">{{ phaseLabel(status.phase) }}</VTag>
          <span v-else>正在读取状态…</span>
          <span v-if="status?.username" class="text-sm text-gray-500">@{{ status.username }}</span>
          <div class="ml-auto flex gap-2">
            <VButton size="sm" :loading="refreshing" :disabled="busy" @click="refresh">刷新</VButton>
            <VButton size="sm" :disabled="busy || !status?.running" @click="control('stop')">停止</VButton>
            <VButton size="sm" type="primary" :loading="busy" @click="control('restart')">应用配置并重启</VButton>
          </div>
        </div>
        <p v-if="status?.error" role="alert" class="text-sm text-red-600">{{ status.error }}</p>
        <p v-if="status?.configPending" class="text-sm text-amber-700">配置已更改，重启 Bot 后生效。</p>
        <p v-if="status?.phase === 'UNCONFIGURED'" class="text-sm text-gray-600">请先配置 Bot Token、发布者和附件存储策略。</p>
        <dl v-if="status" class="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
          <div><dt class="text-gray-500">待处理</dt><dd>{{ status.pendingCount }}</dd></div>
          <div><dt class="text-gray-500">处理失败</dt><dd>{{ status.failedCount }}</dd></div>
          <div><dt class="text-gray-500">上次连接成功</dt><dd>{{ time(status.lastPoll) }}</dd></div>
          <div><dt class="text-gray-500">上次发布成功</dt><dd>{{ time(status.lastPublished) }}</dd></div>
        </dl>
      </div>
    </VCard>
    <VCard title="待处理与失败任务">
      <div class="p-4 overflow-auto">
        <p v-if="!tasks.length" class="text-sm text-gray-500">暂无待处理或失败任务。</p>
        <table v-else class="w-full text-left text-sm">
          <thead><tr><th class="p-2">任务</th><th class="p-2">状态</th><th class="p-2">尝试次数</th><th class="p-2">原因</th><th class="p-2">操作</th></tr></thead>
          <tbody><tr v-for="task in tasks" :key="task.name" class="border-t">
            <td class="p-2 font-mono break-all">{{ task.name }}</td><td class="p-2">{{ task.state === 'FAILED' ? '失败' : '等待处理' }}</td>
            <td class="p-2">{{ task.attempts }}</td><td class="p-2">{{ task.error || '—' }}</td>
            <td class="p-2"><VButton v-if="task.state === 'FAILED'" size="sm" :disabled="busy || !status?.running" @click="retry(task.name)">重试</VButton></td>
          </tr></tbody>
        </table>
        <p v-if="tasks.length" class="mt-3 text-xs text-gray-500">最多显示 100 条。失败任务会保留，修复原因后可手动重试。</p>
      </div>
    </VCard>
    <VCard title="运行日志">
      <div class="p-4 flex flex-col gap-3">
        <div class="flex items-center justify-between gap-3">
          <p class="text-sm text-gray-500">{{ status?.logEnabled ? '保留最近 2 小时内最多 500 条日志。' : '页面日志未启用，可在 Bot 配置中启用并重启。' }}</p>
          <VButton size="sm" :disabled="!logs.length" @click="clearLogs">清空日志</VButton>
        </div>
        <p class="text-sm text-gray-500">Connection / Polling：连接与轮询；Receive：收到更新、入队或忽略原因；Media：下载与上传；Sync：处理结果与重试。正常空轮询每分钟记录一次心跳，调试模式会记录每次请求。可按 update、message 或 task 标识关联同一条消息。</p>
        <div v-if="logs.length" class="max-h-96 overflow-auto rounded border bg-gray-50 font-mono text-xs">
          <div v-for="(entry, index) in logs" :key="index" class="log-entry border-b p-2" :class="{ 'log-error': entry.level === 'ERROR', 'log-warning': entry.level === 'WARN' }">
            <div class="log-meta"><span>{{ time(entry.timestamp) }}</span><strong>{{ entry.level }}</strong><span>{{ entry.source }}</span></div>
            <div class="log-message">{{ entry.message }}</div>
          </div>
        </div>
        <p v-else class="text-sm text-gray-500">暂无日志。</p>
      </div>
    </VCard>
  </div>
</template>

<style scoped>
table { min-width: 680px; }
th, td { padding: 12px 8px; vertical-align: top; }
th:nth-child(2), th:nth-child(3), th:nth-child(5), td:nth-child(2), td:nth-child(3), td:nth-child(5) { white-space: nowrap; }
td:first-child { max-width: 240px; font-family: monospace; overflow-wrap: anywhere; }
td:nth-child(4) { min-width: 180px; overflow-wrap: anywhere; }
.log-entry { font-family: ui-monospace, monospace; line-height: 1.65; }
.log-meta { display: flex; flex-wrap: wrap; gap: 12px; color: #64748b; font-size: 12px; }
.log-message { overflow-wrap: anywhere; white-space: pre-wrap; color: #334155; }
.log-error { background: #fff1f2; }
.log-error .log-message { color: #b91c1c; }
.log-warning { background: #fffbeb; }
.log-warning .log-message { color: #92400e; }
</style>
