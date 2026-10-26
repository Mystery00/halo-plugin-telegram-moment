import { describe, expect, it } from 'vitest'
import { filterLogs, loadDashboard, phaseLabel, restartSucceeded } from '../dashboard'

describe('同步管理状态', () => {
  it('接口失败不能伪装成停止', async () => {
    const request = async () => {
      throw new Error('Network unavailable')
    }
    await expect(loadDashboard(request)).rejects.toThrow('Network unavailable')
  })
  it('配置错误不能显示重启成功', () => {
    expect(restartSucceeded({ phase: 'CONFIG_ERROR' })).toBe(false)
    expect(restartSucceeded({ phase: 'RUNNING' })).toBe(true)
  })
  it('重连和未配置具有不同提示', () => {
    expect(phaseLabel('RECONNECTING')).toBe('正在重连')
    expect(phaseLabel('UNCONFIGURED')).toBe('尚未配置')
  })
})

describe('日志筛选', () => {
  const entries = [
    {
      timestamp: '2026-10-26T12:00:00Z',
      level: 'ERROR',
      source: 'Sync',
      message: 'Failed task=tg-42',
    },
    {
      timestamp: '2026-10-26T12:00:01Z',
      level: 'INFO',
      source: 'Polling',
      message: 'Connection healthy',
    },
    {
      timestamp: '2026-10-26T12:00:02Z',
      level: 'INFO',
      source: 'Sync',
      message: 'Published task=tg-42',
    },
  ]
  it('同时匹配关键词、级别和来源', () => {
    expect(filterLogs(entries, { query: ' TG-42 ', level: 'INFO', source: 'Sync' })).toEqual([
      entries[2],
    ])
  })
  it('允许空筛选并保持原始顺序和输入不变', () => {
    const before = structuredClone(entries)
    expect(filterLogs(entries, { query: ' ', level: '', source: '' })).toEqual(entries)
    expect(entries).toEqual(before)
  })
  it('可检索来源、级别和时间，找不到时返回空列表', () => {
    expect(filterLogs(entries, { query: 'polling', level: '', source: '' })).toEqual([entries[1]])
    expect(filterLogs(entries, { query: 'error', level: '', source: '' })).toEqual([entries[0]])
    expect(filterLogs(entries, { query: '12:00:02', level: '', source: '' })).toEqual([entries[2]])
    expect(filterLogs(entries, { query: 'missing', level: '', source: '' })).toEqual([])
  })
  it('匹配页面显示的本地时间', () => {
    const displayed = new Date(entries[2].timestamp).toLocaleString('zh-CN', { hour12: false })
    expect(filterLogs(entries, { query: displayed, level: '', source: '' })).toEqual([entries[2]])
  })
})
