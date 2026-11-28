<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { useRestaurantCatalog } from '../model/catalog.store'

const catalog = useRestaurantCatalog()
const open = ref(false)
const query = ref('')
const root = ref<globalThis.HTMLElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const input = ref<globalThis.HTMLInputElement>()
const list = ref<globalThis.HTMLElement>()
const panelId = useId()

const visibleCities = computed(() => {
  const value = query.value.trim()
  const matches = (catalog.cities ?? []).filter((city) => city.includes(value))
  const selected = catalog.cityName
  return matches.includes(selected)
    ? [selected, ...matches.filter((city) => city !== selected)]
    : matches
})

watch(query, () => {
  if (list.value) list.value.scrollTop = 0
})

async function toggle() {
  if (open.value) {
    close()
    return
  }
  query.value = ''
  open.value = true
  void catalog.loadCities()
  await nextTick()
  input.value?.focus()
}

function close() {
  open.value = false
  trigger.value?.focus({ preventScroll: true })
}

function choose(city: string) {
  catalog.cityName = city
  close()
}

function chooseOnlyMatch() {
  if (visibleCities.value.length === 1) choose(visibleCities.value[0]!)
}

watch(open, (visible, _, onCleanup) => {
  if (!visible) return
  const dismissOutside = (event: globalThis.Event) => {
    if (!root.value?.contains(event.target as globalThis.Node)) open.value = false
  }
  globalThis.document.addEventListener('pointerdown', dismissOutside)
  globalThis.document.addEventListener('focusin', dismissOutside)
  onCleanup(() => {
    globalThis.document.removeEventListener('pointerdown', dismissOutside)
    globalThis.document.removeEventListener('focusin', dismissOutside)
  })
})
</script>

<template>
  <div ref="root" class="catalog-city-filter" @keydown.esc.stop.prevent="open && close()">
    <button
      ref="trigger"
      class="city-trigger"
      type="button"
      :aria-label="`筛选城市：${catalog.cityName}`"
      :aria-expanded="open"
      :aria-controls="open ? panelId : undefined"
      @click="toggle"
    >
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z" />
        <circle cx="12" cy="10" r="2.5" />
      </svg>
      <span :title="catalog.cityName">{{ catalog.cityName.replace(/市$/, '') }}</span>
      <svg class="city-chevron" viewBox="0 0 20 20" fill="none" aria-hidden="true">
        <path d="m5 8 5 5 5-5" />
      </svg>
    </button>
    <div v-if="open" :id="panelId" class="city-panel">
      <label class="city-search">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 4 4" />
        </svg>
        <input
          ref="input"
          v-model="query"
          aria-label="搜索城市"
          placeholder="搜索城市"
          type="search"
          @keydown.enter.prevent="chooseOnlyMatch"
        />
      </label>
      <p v-if="catalog.citiesLoading" role="status">正在加载城市…</p>
      <p v-else-if="catalog.citiesError" role="status">
        城市列表加载失败
        <button class="retry" type="button" @click="catalog.loadCities">重试</button>
      </p>
      <ul v-else-if="visibleCities.length" ref="list" aria-label="可筛选城市">
        <li v-for="city in visibleCities" :key="city">
          <button type="button" :aria-pressed="catalog.cityName === city" @click="choose(city)">
            {{ city.replace(/市$/, '') }}
            <span v-if="catalog.cityName === city" aria-hidden="true">✓</span>
          </button>
        </li>
      </ul>
      <p v-else role="status">{{ query.trim() ? '没有匹配的城市' : '暂无可选城市' }}</p>
    </div>
  </div>
</template>

<style scoped>
.catalog-city-filter {
  position: relative;
  flex: 0 0 auto;
  margin-inline-end: -10px;
}
.city-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: auto;
  min-width: 91px;
  max-width: 140px;
  height: var(--catalog-search-height);
  padding: 0 13px;
  border: 1px solid #e3e6e9;
  border-radius: 9px;
  background: #fff;
  color: #303946;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.city-trigger span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.city-trigger svg,
.city-search svg {
  flex: none;
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
}
.city-trigger svg {
  color: #a16a28;
}
.city-trigger .city-chevron {
  width: 12px;
  height: 12px;
  color: #65707d;
}
.city-trigger[aria-expanded='true'] {
  color: #925108;
  background: #fff8ed;
  border-color: #e9af6a;
}
.city-trigger[aria-expanded='true'] .city-chevron {
  transform: rotate(180deg);
}
.city-panel {
  position: absolute;
  z-index: 20;
  top: calc(100% + 7px);
  left: 0;
  box-sizing: border-box;
  width: min(228px, calc(100vw - 40px));
  padding: 8px;
  border: 1px solid #e4e7eb;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 6px 22px #1d27391c;
  color: #303946;
}
.city-search {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  margin-bottom: 6px;
  padding: 0 9px;
  border: 1px solid #e7eaee;
  border-radius: 6px;
  background: #f4f5f7;
  color: #697482;
}
.city-search:focus-within {
  border-color: #e5af70;
  background: #fff;
}
.city-search input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: #303946;
  font: inherit;
  font-size: 14px;
}
.city-panel ul {
  max-height: 185px;
  overflow-y: auto;
  margin: 0;
  padding: 0;
  list-style: none;
  scrollbar-width: thin;
}
.city-panel li button {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  min-height: 35px;
  padding: 0 10px;
  border: 0;
  border-radius: 5px;
  background: #fff;
  color: inherit;
  font: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}
.city-panel li button:hover,
.city-panel li button[aria-pressed='true'] {
  background: #fff4e5;
  color: #985309;
}
.city-panel p {
  margin: 0;
  padding: 8px 5px;
  font-size: 13px;
}
.retry {
  padding: 2px 4px;
  border: 0;
  background: transparent;
  color: #985309;
  font: inherit;
  cursor: pointer;
}

@media (max-width: 700px) {
  .catalog-city-filter {
    margin-inline-end: 0;
  }
  .city-trigger {
    gap: 5px;
    min-width: 69px;
    max-width: 96px;
    padding: 0 7px;
  }
  .city-trigger svg {
    width: 13px;
    height: 13px;
  }
  .city-panel li button {
    min-height: 44px;
  }
}
</style>
