import { describe, expect, it } from 'vitest'
import { loadDashboard, phaseLabel, restartSucceeded } from '../dashboard'

describe('同步管理状态', () => {
  it('接口失败不能伪装成停止', async () => {
    const request = async () => { throw new Error('Network unavailable') }
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
