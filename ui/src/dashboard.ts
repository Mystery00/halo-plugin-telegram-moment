export interface BotStatus {
  phase: string
  running: boolean
  username: string
  botId: number
  error: string
  lastPoll: string | null
  lastPublished: string | null
  configPending: boolean
  pendingCount: number
  failedCount: number
  logEnabled: boolean
}
export interface Task {
  name: string
  state: string
  attempts: number
  error: string
}
export interface LogEntry {
  timestamp: string
  level: string
  source: string
  message: string
}
export function logTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}
export function filterLogs(
  entries: LogEntry[],
  filters: { query: string; level: string; source: string },
) {
  const query = filters.query.trim().toLowerCase()
  return entries.filter(
    (entry) =>
      (!filters.level || entry.level === filters.level) &&
      (!filters.source || entry.source === filters.source) &&
      (!query ||
        [entry.timestamp, logTime(entry.timestamp), entry.level, entry.source, entry.message]
          .join(' ')
          .toLowerCase()
          .includes(query)),
  )
}
export const baseUrl = '/apis/console.api.telegram-moment.halo.run/v1alpha1'
export async function loadDashboard(request: (url: string) => Promise<{ data: unknown }>) {
  const [status, tasks, logs] = await Promise.all(
    ['status', 'tasks', 'logs'].map((path) => request(`${baseUrl}/${path}`)),
  )
  return {
    status: status.data as BotStatus,
    tasks: tasks.data as Task[],
    logs: logs.data as LogEntry[],
  }
}
export function restartSucceeded(status: Pick<BotStatus, 'phase'>) {
  return status.phase === 'RUNNING'
}
export function phaseLabel(phase: string) {
  return (
    (
      {
        STOPPED: '已停止',
        STARTING: '正在启动',
        RUNNING: '运行中',
        RECONNECTING: '正在重连',
        UNCONFIGURED: '尚未配置',
        CONFIG_ERROR: '配置有误',
        ERROR: '运行异常',
      } as Record<string, string>
    )[phase] ?? phase
  )
}
