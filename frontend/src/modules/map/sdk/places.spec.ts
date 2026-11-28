import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadAMap } from './amap'
import type { AMapSDK, SDKPlaceResult } from './types'
import { searchAMapPlaces } from './places'
vi.mock('./amap', () => ({ loadAMap: vi.fn() }))

/** SDK替身验证数据边界、取消和超时，不消耗真实搜索配额。 */
describe('高德地点搜索适配', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.resetAllMocks()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })
  async function setup() {
    vi.useFakeTimers()
    let callback!: (status: string, value: SDKPlaceResult) => void
    const options = vi.fn()
    const sdk = {
      plugin: (_names: string[], ready: () => void) => ready(),
      PlaceSearch: class {
        constructor(config: unknown) {
          options(config)
        }
        search(_keyword: string, cb: typeof callback) {
          callback = cb
        }
      },
    } as unknown as AMapSDK
    vi.mocked(loadAMap).mockResolvedValue(sdk)
    const controller = new AbortController()
    const pending = searchAMapPlaces('测试餐厅', 2, controller.signal)
    await Promise.resolve()
    return {
      pending,
      controller,
      options,
      finish: (status: string, value: SDKPlaceResult) => callback(status, value),
    }
  }
  const poi = {
    id: 'poi-1',
    name: '测试餐厅',
    address: '测试地址',
    location: { getLng: () => 104.1, getLat: () => 30.6 },
  }
  it('只请求餐饮基础数据，去重并排除缺少位置的条目', async () => {
    const { pending, finish, options } = await setup()
    expect(options).toHaveBeenCalledWith(
      expect.objectContaining({
        type: '050000',
        pageIndex: 2,
        extensions: 'base',
        citylimit: false,
      }),
    )
    expect(options.mock.calls[0]![0]).not.toHaveProperty('map')
    finish('complete', {
      poiList: { count: '3', pois: [poi, poi, { ...poi, id: 'bad', location: undefined }] },
    })
    const result = await pending
    expect(result.places).toHaveLength(1)
    expect(result.places[0]).not.toHaveProperty('score')
    expect(result.total).toBe(3)
    expect(vi.getTimerCount()).toBe(0)
  })
  it('无结果是空列表，服务失败保持独立错误', async () => {
    const first = await setup()
    first.finish('no_data', {})
    expect(await first.pending).toEqual({ places: [], total: 0 })
    const second = await setup()
    const failure = expect(second.pending).rejects.toThrow('暂不可用')
    second.finish('error', { info: 'SECRET_MUST_NOT_APPEAR' })
    await failure
  })
  it.each([
    ['510100', '成都市', { cityname: '成都市', adname: '都江堰市' }, '500103'],
    ['500000', '重庆市', { pname: '重庆市' }, '510107'],
    ['510100', '成都市', { adcode: '510181', cityname: '成都市' }, '500103'],
  ] as const)(
    '限定城市 %s，不采用外地或无法验证归属的门店',
    async (adcode, name, local, outside) => {
      vi.stubEnv('VITE_AMAP_SERVICE_HOST', '/_AMapService')
      const raw = { ...poi, location: '104.1,30.6' }
      const fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          status: '1',
          count: '3',
          pois: [
            { ...raw, ...local },
            { ...raw, id: 'outside', adcode: outside },
            { ...raw, id: 'unknown' },
          ],
        }),
      })
      vi.stubGlobal('fetch', fetch)
      const result = await searchAMapPlaces('测试餐厅', 2, new AbortController().signal, {
        adcode,
        name,
      })
      const request = new URL(fetch.mock.calls[0]![0], 'http://localhost')
      expect(request.pathname).toBe('/_AMapService/v3/place/text')
      expect(request.searchParams.get('city')).toBe(adcode)
      expect(request.searchParams.get('citylimit')).toBe('true')
      expect(request.searchParams.get('page')).toBe('2')
      expect(loadAMap).not.toHaveBeenCalled()
      expect(result.places).toEqual([
        expect.objectContaining({
          id: poi.id,
          cityName: name,
          district: expect.stringContaining(name),
          latitude: 30.6,
          longitude: 104.1,
        }),
      ])
    },
  )
  it('取消及超时后忽略SDK迟到回调并清理计时', async () => {
    const first = await setup()
    const aborted = expect(first.pending).rejects.toHaveProperty('name', 'AbortError')
    first.controller.abort()
    first.finish('complete', { poiList: { count: 1, pois: [poi] } })
    await aborted
    const second = await setup()
    const timeout = expect(second.pending).rejects.toThrow('超时')
    await vi.advanceTimersByTimeAsync(15000)
    await timeout
    expect(vi.getTimerCount()).toBe(0)
  })
})
