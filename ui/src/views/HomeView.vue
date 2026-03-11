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

// Bot 状态
const status = ref<BotStatus>({ running: false, username: '' })
const restarting = ref(false)

// 发布者配置
const selectedOwner = ref('')
const ownerSaving = ref(false)

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

onMounted(() => {
  fetchStatus()
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
  </div>
</template>
