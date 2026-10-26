import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import HomeView from '../views/HomeView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }))
vi.mock('@halo-dev/api-client', () => ({ axiosInstance: api }))
vi.mock('@halo-dev/components', () => ({
  VButton: {
    props: ['disabled', 'loading', 'type', 'size'],
    template: '<button :disabled="disabled || loading"><slot /></button>',
  },
  Toast: { success: vi.fn(), error: vi.fn() },
}))

const status = {
  phase: 'RUNNING',
  running: true,
  username: 'demo_bot',
  pendingCount: 0,
  failedCount: 0,
  logEnabled: true,
}
const entry = {
  timestamp: '2026-10-26T12:00:00Z',
  level: 'INFO',
  source: 'Polling',
  message: 'Connection healthy',
}
let wrapper: VueWrapper
function response(url: string) {
  return { data: url.endsWith('/status') ? status : url.endsWith('/tasks') ? [] : [entry] }
}
async function click(text: string) {
  const button = wrapper.findAll('button').find((button) => button.text() === text)
  expect(button, `应存在按钮：${text}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  api.get.mockImplementation(async (url: string) => response(url))
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
})

describe('管理页刷新', () => {
  it('暂停冻结自动刷新，手动刷新仍有效，恢复立即获取', async () => {
    wrapper = mount(HomeView)
    await flushPromises()
    await click('暂停刷新')
    await vi.advanceTimersByTimeAsync(20000)
    expect(api.get).toHaveBeenCalledTimes(3)
    await click('刷新')
    expect(api.get).toHaveBeenCalledTimes(6)
    await click('恢复刷新')
    expect(api.get).toHaveBeenCalledTimes(9)
  })
  it('在途请求结束后补上快速暂停恢复所请求的刷新', async () => {
    const pending: (() => void)[] = []
    api.get.mockImplementation(
      (url: string) => new Promise((resolve) => pending.push(() => resolve(response(url)))),
    )
    wrapper = mount(HomeView)
    await click('暂停刷新')
    await click('恢复刷新')
    api.get.mockImplementation(async (url: string) => response(url))
    pending.forEach((resolve) => resolve())
    await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(6)
    expect(wrapper.text()).toContain('@demo_bot')
  })
  it('首次请求失败显示未知状态，不伪装为空队列', async () => {
    api.get.mockRejectedValue(new Error('Offline'))
    wrapper = mount(HomeView)
    await flushPromises()
    expect(wrapper.text()).toContain('状态未知')
    expect(wrapper.text()).toContain('任务数据暂不可用')
    expect(wrapper.text()).not.toContain('暂无待处理或失败任务')
  })
  it('刷新失败保留上次日志，清空取消不调用接口，清空失败保留内容', async () => {
    wrapper = mount(HomeView)
    await flushPromises()
    api.get.mockRejectedValue(new Error('Offline'))
    await click('刷新')
    expect(wrapper.text()).toContain('当前显示上次成功获取的数据')
    expect(wrapper.text()).toContain('Connection healthy')
    await click('清空日志')
    await click('取消')
    expect(api.delete).not.toHaveBeenCalled()
    api.delete.mockRejectedValue(new Error('Offline'))
    await click('清空日志')
    await click('确认清空')
    expect(wrapper.text()).toContain('Connection healthy')
  })
})
