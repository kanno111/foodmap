import { loadAMap } from './amap'

export interface AMapPlace {
  id: string
  name: string
  address: string
  district: string
  cityName?: string
  category: string
  longitude: number
  latitude: number
}

export interface AMapSearchCity {
  adcode: string
  name: string
}

interface PlaceFields {
  id: string
  name: string
  address?: string | string[]
  pname?: string
  cityname?: string
  adname?: string
  adcode?: string
  type?: string
}

interface PlaceCandidate extends PlaceFields {
  longitude?: number
  latitude?: number
}

interface PlaceSearchResult {
  places: AMapPlace[]
  total: number
}

function matchesCity(poi: PlaceFields, city: AMapSearchCity): boolean {
  if (poi.adcode && /^\d{6}$/.test(poi.adcode)) {
    // 直辖市使用省级前缀，其他城市使用地级前缀，均包含下辖区县及县级市。
    const prefix = city.adcode.slice(0, city.adcode.endsWith('0000') ? 2 : 4)
    return poi.adcode.startsWith(prefix)
  }
  return poi.cityname === city.name || (city.adcode.endsWith('0000') && poi.pname === city.name)
}

function normalizePlaces(
  candidates: PlaceCandidate[],
  count: string | number | undefined,
  city?: AMapSearchCity,
): PlaceSearchResult {
  const seen = new Set<string>()
  const places: AMapPlace[] = []
  for (const poi of candidates) {
    const { longitude, latitude } = poi
    if (
      typeof poi.id !== 'string' ||
      !poi.id ||
      typeof poi.name !== 'string' ||
      !poi.name ||
      longitude === undefined ||
      latitude === undefined ||
      !Number.isFinite(longitude) ||
      !Number.isFinite(latitude) ||
      Math.abs(longitude) > 180 ||
      Math.abs(latitude) > 90 ||
      (city && !matchesCity(poi, city)) ||
      seen.has(poi.id)
    )
      continue

    seen.add(poi.id)
    places.push({
      id: poi.id,
      name: poi.name,
      address: typeof poi.address === 'string' ? poi.address : '',
      ...(city ? { cityName: city.name } : {}),
      district: [
        ...new Set(
          [poi.pname, poi.cityname, poi.adname].filter((name) => typeof name === 'string' && name),
        ),
      ].join(' · '),
      category:
        (typeof poi.type === 'string' && poi.type.split(';').filter(Boolean).at(-1)) || '餐饮地点',
      longitude,
      latitude,
    })
  }
  const total = Number(count)
  return { places, total: Number.isFinite(total) ? Math.max(places.length, total) : places.length }
}

/** SDK回调不保留全部区划字段，投稿通过同源代理读取高德原生JSON，供页面核对归属。 */
async function searchPlacesInCity(
  keyword: string,
  page: number,
  signal: AbortSignal,
  city: AMapSearchCity,
): Promise<PlaceSearchResult> {
  const serviceHost = import.meta.env.VITE_AMAP_SERVICE_HOST
  if (!serviceHost?.startsWith('/') || serviceHost.startsWith('//')) {
    throw new Error('高德地点搜索尚未配置同源代理。')
  }
  const request = new globalThis.AbortController()
  const abort = () => request.abort(signal.reason)
  if (signal.aborted) abort()
  signal.addEventListener('abort', abort, { once: true })
  const timer = globalThis.setTimeout(
    () => request.abort(new Error('高德地点搜索超时，请重试。')),
    15000,
  )
  const query = new URLSearchParams({
    keywords: keyword,
    page: String(page),
    city: city.adcode,
    citylimit: 'true',
  })
  try {
    const response = await globalThis.fetch(`${serviceHost}/v3/place/text?${query}`, {
      credentials: 'omit',
      signal: request.signal,
    })
    if (!response.ok) throw new Error()
    const result = (await response.json()) as {
      status?: string
      count?: string
      pois?: (PlaceFields & { location?: string | string[] })[]
    }
    if (result.status !== '1' || !Array.isArray(result.pois)) throw new Error()
    return normalizePlaces(
      result.pois.map(({ location, ...poi }) => {
        const [longitude, latitude] = typeof location === 'string' ? location.split(',') : []
        return {
          ...poi,
          longitude: longitude?.trim() ? Number(longitude) : undefined,
          latitude: latitude?.trim() ? Number(latitude) : undefined,
        }
      }),
      result.count,
      city,
    )
  } catch {
    if (request.signal.aborted) throw request.signal.reason
    throw new Error('高德地点搜索暂不可用，请重试。')
  } finally {
    globalThis.clearTimeout(timer)
    signal.removeEventListener('abort', abort)
  }
}

/** 地点搜索不绑定SDK面板或地图，防止自动绘制第三方评分及重复点位。只在主动输入后调用。 */
export function searchAMapPlaces(
  keyword: string,
  page: number,
  signal: AbortSignal,
  city?: AMapSearchCity,
): Promise<PlaceSearchResult> {
  if (city) return searchPlacesInCity(keyword, page, signal, city)
  return new Promise((resolve, reject) => {
    let finished = false
    const timer = globalThis.setTimeout(() => fail(new Error('高德地点搜索超时，请重试。')), 15000)
    function clean() {
      finished = true
      globalThis.clearTimeout(timer)
      signal.removeEventListener('abort', abort)
    }
    function fail(reason: Error) {
      if (!finished) {
        clean()
        reject(reason)
      }
    }
    function abort() {
      fail(new globalThis.DOMException('请求已取消', 'AbortError'))
    }
    if (signal.aborted) {
      abort()
      return
    }
    signal.addEventListener('abort', abort, { once: true })
    void loadAMap()
      .then((sdk) => {
        if (finished) return
        sdk.plugin(['AMap.PlaceSearch'], () => {
          if (finished) return
          try {
            // 原有地图搜索继续优先成都，不传当前视口或用户精确位置。
            const search = new sdk.PlaceSearch({
              city: '成都市',
              citylimit: false,
              type: '050000',
              pageSize: 10,
              pageIndex: page,
              extensions: 'base',
            })
            search.search(keyword, (status, result) => {
              if (finished) return
              if (status === 'no_data') {
                clean()
                resolve({ places: [], total: 0 })
                return
              }
              if (status !== 'complete' || !result.poiList) {
                fail(new Error('高德地点搜索暂不可用，本站结果仍可使用。'))
                return
              }
              const found = normalizePlaces(
                result.poiList.pois.map((poi) => ({
                  ...poi,
                  longitude: poi.location?.getLng(),
                  latitude: poi.location?.getLat(),
                })),
                result.poiList.count,
              )
              clean()
              resolve(found)
            })
          } catch {
            fail(new Error('高德地点搜索暂不可用，请重试。'))
          }
        })
      })
      .catch(() => fail(new Error('高德地点搜索尚不可用，请检查地图配置或网络。')))
  })
}
