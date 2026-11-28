<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import cityRows from '../model/submissionCities.json'
import type { AMapSearchCity } from '../sdk/places'

const selected = defineModel<AMapSearchCity>({ required: true })
defineProps<{ disabled?: boolean }>()

const cities = cityRows.map(([adcode, name, province]) => ({ adcode, name, province }))
const suggestedCodes = [
  '510100',
  '500000',
  '610100',
  '510700',
  '110000',
  '310000',
  '440100',
  '440300',
]
const open = ref(false)
const query = ref('')
const picker = ref<globalThis.HTMLElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const input = ref<globalThis.HTMLInputElement>()
const panelId = useId()
const visibleCities = computed(() => {
  const text = query.value.trim()
  if (text)
    return cities.filter(({ name, province }) => name.includes(text) || province.includes(text))
  const codes = new Set([selected.value.adcode, ...suggestedCodes])
  return [...codes].flatMap((code) => cities.find(({ adcode }) => adcode === code) ?? [])
})

async function toggle() {
  if (open.value) {
    close()
    return
  }
  open.value = true
  query.value = ''
  await nextTick()
  input.value?.focus()
}

function close() {
  open.value = false
  trigger.value?.focus()
}

function choose(city: AMapSearchCity) {
  if (city.adcode !== selected.value.adcode) selected.value = city
  close()
}

watch(open, (visible, _, onCleanup) => {
  if (!visible) return
  // 某些移动浏览器点击按钮时不转移焦点，点击选项不应因输入框失焦而丢失。
  const dismissOutside = (event: globalThis.Event) => {
    if (!picker.value?.contains(event.target as globalThis.Node)) open.value = false
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
  <div ref="picker" class="city-picker">
    <span class="city-label">搜索城市</span>
    <button
      ref="trigger"
      class="city-trigger"
      type="button"
      :aria-label="`搜索城市：${selected.name}`"
      :aria-expanded="open"
      :aria-controls="panelId"
      :disabled="disabled"
      @click="toggle"
    >
      <span>{{ selected.name.replace(/市$/, '') }}</span>
      <span class="chevron" aria-hidden="true"></span>
    </button>
    <div v-if="open" :id="panelId" class="city-panel" @keydown.esc.stop.prevent="close">
      <input
        ref="input"
        v-model="query"
        type="search"
        aria-label="搜索城市名称"
        placeholder="搜索城市名称"
        @keydown.enter.prevent.stop
      />
      <div v-if="visibleCities.length" class="city-options" aria-label="城市列表">
        <button
          v-for="city in visibleCities"
          :key="city.adcode"
          type="button"
          :aria-pressed="city.adcode === selected.adcode"
          @click="choose(city)"
        >
          {{ city.name }} <small>{{ city.province }}</small>
          <span v-if="city.adcode === selected.adcode" class="check" aria-hidden="true">✓</span>
        </button>
      </div>
      <p v-else role="status">没有找到该城市</p>
    </div>
  </div>
</template>

<style scoped>
.city-picker {
  position: relative;
  display: grid;
  gap: 8px;
  min-width: 0;
  max-width: 124px;
}
.city-label {
  font-size: 13px;
}
.city-trigger,
.city-panel input {
  border: 1px solid #dedede;
  border-radius: 7px;
  background: #fff;
  color: inherit;
  font: inherit;
  padding: 10px;
}
.city-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 76px;
  cursor: pointer;
}
.city-trigger:disabled {
  opacity: 0.5;
  cursor: default;
}
.city-trigger span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.city-trigger[aria-expanded='true'] {
  border-color: #e6af66;
  background: #fff8ed;
  color: #9c601b;
}
.chevron {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: translateY(-2px) rotate(45deg);
}
[aria-expanded='true'] .chevron {
  transform: translateY(2px) rotate(225deg);
}
.city-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 2;
  box-sizing: border-box;
  width: 244px;
  padding: 10px;
  border: 1px solid #dedede;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 5px 18px rgb(35 42 49 / 12%);
}
.city-panel input {
  box-sizing: border-box;
  width: 100%;
}
.city-options {
  max-height: 216px;
  overflow-y: auto;
  margin-top: 6px;
}
.city-options button {
  display: block;
  width: 100%;
  padding: 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.city-options button:hover,
.city-options button[aria-pressed='true'] {
  background: #fff8ed;
  color: #9c601b;
}
.city-options small,
.city-panel p {
  color: #68747f;
  font-size: 12px;
}
.city-options small {
  margin-left: 4px;
}
.check {
  float: right;
}
@media (max-width: 540px) {
  .city-picker {
    position: static;
    max-width: 94px;
  }
  .city-panel {
    width: min(244px, 100%);
  }
}
</style>
