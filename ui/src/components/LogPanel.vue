<script setup lang="ts">
import { computed, ref } from 'vue'
import { VButton } from '@halo-dev/components'
import SearchIcon from '~icons/ri/search-line'
import PauseIcon from '~icons/ri/pause-line'
import PlayIcon from '~icons/ri/play-line'
import DeleteIcon from '~icons/ri/delete-bin-line'
import RefreshIcon from '~icons/ri/refresh-line'
import { filterLogs, logTime, type LogEntry } from '../dashboard'

const props = defineProps<{
  entries: LogEntry[]
  enabled?: boolean
  loaded: boolean
  loadError: boolean
  paused: boolean
  busy: boolean
}>()
defineEmits<{ togglePause: []; clear: [] }>()
const query = ref('')
const level = ref('')
const source = ref('')
const visible = computed(() =>
  filterLogs(props.entries, { query: query.value, level: level.value, source: source.value }),
)
const sources = computed(() =>
  [
    ...new Set([...props.entries.map((entry) => entry.source), source.value].filter(Boolean)),
  ].sort(),
)
const levels = computed(() => [
  ...new Set(['ERROR', 'WARN', 'INFO', 'DEBUG', ...props.entries.map((entry) => entry.level)]),
])
const filtered = computed(() => !!(query.value.trim() || level.value || source.value))
function reset() {
  query.value = ''
  level.value = ''
  source.value = ''
}
</script>

<template>
  <section class="log-panel" aria-labelledby="log-title">
    <div class="log-toolbar">
      <div class="log-heading">
        <div>
          <h2 id="log-title">运行日志</h2>
          <p>最近 2 小时 · 最多 500 条，记录连接、接收、处理与发布过程。</p>
        </div>
        <VButton
          size="sm"
          :type="paused ? 'primary' : 'default'"
          :aria-pressed="paused"
          @click="$emit('togglePause')"
        >
          <component :is="paused ? PlayIcon : PauseIcon" aria-hidden="true" />{{
            paused ? '恢复刷新' : '暂停刷新'
          }}
        </VButton>
      </div>
      <div class="filters">
        <label class="search"
          ><SearchIcon aria-hidden="true" /><input
            v-model="query"
            type="search"
            aria-label="搜索日志"
            placeholder="搜索消息、update 或 task"
        /></label>
        <select v-model="level" aria-label="日志级别">
          <option value="">全部级别</option>
          <option v-for="item in levels" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="source" aria-label="日志来源">
          <option value="">全部来源</option>
          <option v-for="item in sources" :key="item" :value="item">{{ item }}</option>
        </select>
        <button v-if="filtered" class="text-action" @click="reset">重置筛选</button>
      </div>
    </div>
    <div v-if="loaded && !enabled" class="log-notice" role="status">
      页面日志未启用，请在插件配置中启用并重启 Bot。
    </div>
    <div v-if="paused" class="log-notice" role="status">
      已暂停页面自动刷新，Bot 仍会继续同步。手动刷新可查看最新数据。
    </div>
    <div class="log-scroll" tabindex="0" aria-label="日志列表">
      <table class="log-table">
        <thead>
          <tr>
            <th scope="col">时间</th>
            <th scope="col">级别</th>
            <th scope="col">来源</th>
            <th scope="col">内容</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(entry, index) in visible"
            :key="index"
            :class="{ 'is-error': entry.level === 'ERROR', 'is-warning': entry.level === 'WARN' }"
          >
            <td class="entry-time">
              <time :datetime="entry.timestamp">{{ logTime(entry.timestamp) }}</time>
            </td>
            <td class="entry-level">
              <span class="level" :class="`level-${entry.level.toLowerCase()}`">{{
                entry.level
              }}</span>
            </td>
            <td class="entry-source" :title="entry.source">{{ entry.source }}</td>
            <td class="entry-message">{{ entry.message }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!visible.length" class="log-empty">
        <template v-if="!loaded">{{
          loadError ? '日志数据暂不可用，请重试。' : '正在读取日志…'
        }}</template>
        <template v-else-if="filtered"
          ><strong>没有匹配的日志</strong>
          <p>尝试其他关键词，或重置筛选条件。</p>
          <button class="text-action" @click="reset">重置筛选</button></template
        >
        <template v-else
          ><strong>{{ enabled ? '暂无日志' : '页面日志未启用' }}</strong>
          <p>
            {{
              enabled
                ? '收到更新或连接状态变化后，处理记录会出现在这里。'
                : '启用后可在这里查看连接与消息处理过程。'
            }}
          </p></template
        >
      </div>
    </div>
    <footer class="log-footer">
      <span class="refresh-hint"
        ><component :is="paused ? PauseIcon : RefreshIcon" aria-hidden="true" />{{
          paused ? '自动刷新已暂停' : '每 10 秒刷新'
        }}<span v-if="loaded" class="entry-count"
          >{{ visible.length }} / {{ entries.length }} 条</span
        ></span
      >
      <VButton size="sm" :disabled="busy || !entries.length" @click="$emit('clear')"
        ><DeleteIcon aria-hidden="true" />清空日志</VButton
      >
    </footer>
    <details class="source-help">
      <summary>日志来源说明</summary>
      <p>
        Connection /
        Polling：连接与轮询；Receive：收到更新、入队或忽略原因；Media：下载与上传；Sync：处理结果与重试。可用
        update、message 或 task
        标识检索同一条消息。正常空轮询每分钟记录一次心跳，调试模式记录每次请求。
      </p>
    </details>
  </section>
</template>

<style scoped>
.log-panel {
  background: white;
  border: 1px solid #e8edf2;
  border-radius: 6px;
  overflow: hidden;
}
.log-toolbar {
  padding: 20px;
}
.log-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}
p {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 14px;
  line-height: 1.6;
}
.filters {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  align-items: center;
  flex-wrap: wrap;
}
.search {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #d9e0e8;
  border-radius: 5px;
  padding: 0 12px;
  width: 340px;
  max-width: 100%;
  color: #64748b;
  background: white;
}
.search input {
  background: transparent;
  border: 0;
  outline: none;
  min-width: 0;
  width: 100%;
  padding: 9px 0;
  font: inherit;
}
.search:focus-within,
select:focus-visible {
  outline: 2px solid #34b991;
  outline-offset: 2px;
}
select {
  border: 1px solid #d9e0e8;
  border-radius: 5px;
  background: white;
  padding: 9px 28px 9px 12px;
  font: inherit;
  max-width: 100%;
}
.text-action {
  color: #047857;
  padding: 6px;
  font-size: 14px;
  cursor: pointer;
  background: none;
  border: 0;
}
.text-action:hover {
  text-decoration: underline;
}
.log-notice {
  padding: 10px 20px;
  background: #fffbeb;
  color: #854d0e;
  font-size: 13px;
  border-top: 1px solid #fef3c7;
}
.log-scroll {
  max-height: min(500px, calc(100dvh - 540px));
  overflow: auto;
  min-height: 260px;
}
.log-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}
th {
  background: #f5f7fa;
  color: #64748b;
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  padding: 14px 16px;
  position: sticky;
  top: 0;
  z-index: 1;
}
th:first-child {
  width: 190px;
  padding-left: 20px;
}
th:nth-child(2) {
  width: 90px;
}
th:nth-child(3) {
  width: 115px;
}
td {
  padding: 14px 16px;
  border-bottom: 1px solid #edf0f4;
  vertical-align: top;
  color: #475569;
  line-height: 1.7;
}
td:first-child {
  padding-left: 20px;
}
.entry-time {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.entry-level,
.entry-source,
.entry-message {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 13px;
}
.entry-source {
  overflow-wrap: anywhere;
}
.entry-message {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.level {
  display: inline-block;
  border-radius: 3px;
  padding: 0 7px;
  background: #e9edf3;
  color: #475569;
  font-size: 12px;
}
.level-info {
  background: #e5efff;
  color: #1d4ed8;
}
.level-error {
  background: #fee2e2;
  color: #b91c1c;
}
.level-warn {
  background: #fef3c7;
  color: #92400e;
}
.is-error {
  background: #fff5f5;
}
.is-error .entry-message {
  color: #b91c1c;
}
.is-warning {
  background: #fffcf0;
}
.is-warning .entry-message {
  color: #92400e;
}
.log-empty {
  padding: 64px 24px;
  text-align: center;
  color: #64748b;
}
.log-empty strong {
  font-weight: 500;
  color: #334155;
}
.log-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid #edf0f4;
}
.refresh-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #64748b;
  font-size: 13px;
  flex-wrap: wrap;
}
.entry-count {
  margin-left: 8px;
}
.source-help {
  border-top: 1px solid #edf0f4;
  padding: 12px 20px;
  font-size: 13px;
  color: #64748b;
}
.source-help summary {
  cursor: pointer;
  width: fit-content;
}
@media (min-width: 1350px) {
  .log-toolbar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px 16px;
  }
  .log-heading {
    display: contents;
  }
  .log-heading > div {
    grid-column: 1;
    grid-row: 1 / 3;
    align-self: center;
  }
  .log-heading p {
    max-width: 480px;
  }
  .log-heading > button {
    grid-column: 2;
    grid-row: 1;
    justify-self: end;
  }
  .filters {
    grid-column: 2;
    grid-row: 2;
    justify-content: flex-end;
    margin-top: 0;
    max-width: 600px;
  }
  .search {
    width: 300px;
  }
}
@media (max-width: 1000px) {
  th:first-child {
    width: 165px;
  }
  th:nth-child(2) {
    width: 75px;
  }
  th:nth-child(3) {
    width: 100px;
  }
  td,
  th {
    padding: 12px 10px;
  }
}
@media (max-width: 640px) {
  .log-toolbar {
    padding: 16px;
  }
  .log-heading {
    flex-wrap: wrap;
  }
  .search {
    width: 100%;
  }
  select {
    flex: 1;
    min-width: 0;
  }
  .log-table,
  tbody {
    display: block;
  }
  thead {
    display: none;
  }
  tr {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 6px 10px;
    padding: 14px 16px;
    border-bottom: 1px solid #edf0f4;
  }
  td,
  td:first-child {
    padding: 0;
    border: 0;
  }
  .entry-source {
    grid-column: 1 / -1;
    color: #64748b;
  }
  .entry-message {
    grid-column: 1 / -1;
  }
  .log-footer {
    padding: 12px 16px;
  }
  .entry-count {
    margin-left: 0;
  }
}
</style>
