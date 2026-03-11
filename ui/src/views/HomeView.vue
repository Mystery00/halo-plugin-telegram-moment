<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { VButton, VCard, VTag, Toast } from '@halo-dev/components'
import axios from 'axios'

const BASE_URL = '/apis/telegram-moment/v1alpha1/bot'
const CONFIGMAP_URL = '/api/v1alpha1/configmaps/telegram-moment-configmap'
const USERS_API = '/apis/api.console.halo.run/v1alpha1/users'

interface BotStatus {
  running: boolean
  username: string
}

interface LogEntry {
  timestamp: string
  level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG'
  source: string
  message: string
}

// Bot 状态
const status = ref<BotStatus>({ running: false, username: '' })
const restarting = ref(false)

// 发布者配置
const selectedOwner = ref('')
const ownerSaving = ref(false)

// 运行日志
const logs = ref<LogEntry[]>([])
const logsLoading = ref(false)
const logsClearing = ref(false)

async function fetchStatus() {
  try {
    const { data } = await axios.get<BotStatus>(`${BASE_URL}/status`)
    status.value = data
  } catch {
    status.value = { running: false, username: '' }
  }
}

async function restartBot() {
  restarting.value = true
  try {
    await axios.post(`${BASE_URL}/restart`)
    Toast.success('Bot 重启指令已发送，请稍后刷新状态')
    setTimeout(fetchStatus, 3000)
  } catch {
    Toast.error('重启失败，请检查配置后重试')
  } finally {
    restarting.value = false
  }
}

async function fetchLogs() {
  logsLoading.value = true
  try {
    const { data } = await axios.get<LogEntry[]>(`${BASE_URL}/logs`)
    // 最新的日志排在最前面
    logs.value = [...data].reverse()
  } catch {
    Toast.error('获取日志失败')
  } finally {
    logsLoading.value = false
  }
}

async function clearLogs() {
  logsClearing.value = true
  try {
    await axios.delete(`${BASE_URL}/logs`)
    logs.value = []
    Toast.success('日志已清空')
  } catch {
    Toast.error('清空日志失败')
  } finally {
    logsClearing.value = false
  }
}

function formatTime(timestamp: string) {
  return new Date(timestamp).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

onMounted(() => {
  fetchStatus()
  fetchLogs()
})
</script>

<template>
  <div class="p-4 flex flex-col gap-4">
    <!-- Bot 状态卡片 -->
    <VCard title="Bot 状态">
      <div class="flex items-center gap-3 px-4 py-3">
        <VTag :type="status.running ? 'success' : 'danger'">
          {{ status.running ? '运行中' : '已停止' }}
        </VTag>
        <span v-if="status.username" class="text-sm text-gray-500">
          @{{ status.username }}
        </span>
        <div class="ml-auto flex gap-2">
          <VButton size="sm" @click="fetchStatus">刷新状态</VButton>
          <VButton size="sm" type="primary" :loading="restarting" @click="restartBot">
            重启 Bot
          </VButton>
        </div>
      </div>
    </VCard>

    <!-- 运行日志卡片 -->
    <VCard title="运行日志">
      <div class="px-4 py-3 flex flex-col gap-3">
        <div class="flex items-center justify-between">
          <p class="text-sm text-gray-500">
            需在「Bot 配置」设置中开启「启用日志记录」并重启 Bot 后生效，最多保留 500 条、2 小时内的日志。
          </p>
          <div class="flex gap-2 ml-4 shrink-0">
            <VButton size="sm" :loading="logsLoading" @click="fetchLogs">刷新</VButton>
            <VButton size="sm" :loading="logsClearing" :disabled="!logs.length" @click="clearLogs">清空</VButton>
          </div>
        </div>

        <!-- 日志列表 -->
        <div
          v-if="logs.length"
          class="rounded-md border border-gray-200 bg-gray-50 overflow-auto max-h-96 font-mono text-xs"
        >
          <div
            v-for="(entry, idx) in logs"
            :key="idx"
            class="flex gap-2 px-3 py-1 border-b border-gray-100 last:border-0"
            :class="{
              'bg-red-50': entry.level === 'ERROR',
              'bg-yellow-50': entry.level === 'WARN',
            }"
          >
            <span class="text-gray-400 shrink-0 w-36">{{ formatTime(entry.timestamp) }}</span>
            <span
              class="font-semibold shrink-0 w-12"
              :class="{
                'text-red-600': entry.level === 'ERROR',
                'text-yellow-600': entry.level === 'WARN',
                'text-blue-600': entry.level === 'INFO',
                'text-gray-500': entry.level === 'DEBUG',
              }"
            >{{ entry.level }}</span>
            <span class="text-gray-400 shrink-0 w-32 truncate">{{ entry.source }}</span>
            <span class="text-gray-700 break-all">{{ entry.message }}</span>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-else class="rounded-md border border-dashed border-gray-200 py-8 text-center text-sm text-gray-400">
          暂无日志，请确认已开启「启用日志记录」并重启 Bot
        </div>
      </div>
    </VCard>
  </div>
</template>
