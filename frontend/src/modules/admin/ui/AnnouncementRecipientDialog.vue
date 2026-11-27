<script setup lang="ts">
import { onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import {
  ANNOUNCEMENT_RECIPIENT_LIMIT,
  searchAnnouncementRecipients,
  type AnnouncementRecipient,
} from '@/modules/announcement'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

const emit = defineEmits<{ save: [recipients: AnnouncementRecipient[]] }>()
const dialog = useTemplateRef('dialog')
const mode = ref<'all' | 'selected'>('all')
const keyword = ref('')
const selected = ref<AnnouncementRecipient[]>([])
const rows = ref<AnnouncementRecipient[]>([])
const page = ref(1)
const hasNext = ref(false)
const loading = ref(false)
const error = ref('')
const limit = ANNOUNCEMENT_RECIPIENT_LIMIT
let timer: ReturnType<typeof globalThis.setTimeout> | undefined
let active: InstanceType<typeof globalThis.AbortController> | undefined

function cancelSearch() {
  globalThis.clearTimeout(timer)
  active?.abort()
  active = undefined
}

async function search(next: number) {
  cancelSearch()
  const token = authToken.value
  if (!token || !dialog.value?.open || mode.value !== 'selected') return
  const request = new globalThis.AbortController()
  active = request
  page.value = next
  rows.value = []
  hasNext.value = false
  error.value = ''
  loading.value = true
  try {
    const result = await searchAnnouncementRecipients(
      keyword.value.trim(),
      next,
      token,
      request.signal,
    )
    if (active !== request) return
    rows.value = result.records
    hasNext.value = result.hasNext
  } catch (reason) {
    if (active === request) error.value = toErrorMessage(reason)
  } finally {
    if (active === request) loading.value = false
  }
}

watch(
  [mode, keyword],
  () => {
    cancelSearch()
    error.value = ''
    rows.value = []
    hasNext.value = false
    loading.value = !!dialog.value?.open && mode.value === 'selected'
    if (loading.value) timer = globalThis.setTimeout(() => void search(1), 220)
  },
  { flush: 'sync' },
)

function open(recipients: AnnouncementRecipient[]) {
  if (!dialog.value || dialog.value.open) return
  selected.value = [...recipients]
  keyword.value = ''
  mode.value = recipients.length ? 'selected' : 'all'
  dialog.value.showModal()
  if (mode.value === 'selected') void search(1)
}

function close() {
  cancelSearch()
  dialog.value?.close()
  selected.value = []
  rows.value = []
}

function isSelected(id: string) {
  return selected.value.some((user) => user.id === id)
}

function remove(id: string) {
  selected.value = selected.value.filter((user) => user.id !== id)
}

function toggle(user: AnnouncementRecipient) {
  if (isSelected(user.id)) remove(user.id)
  else if (selected.value.length < limit) selected.value.push(user)
}

function save() {
  if (mode.value === 'selected' && !selected.value.length) return
  emit('save', mode.value === 'all' ? [] : [...selected.value])
  close()
}

watch(authToken, close, { flush: 'sync' })
onBeforeUnmount(close)
defineExpose({ open })
</script>

<template>
  <dialog
    ref="dialog"
    class="recipient-dialog"
    aria-labelledby="announcement-recipient-title"
    @cancel.prevent="close"
  >
    <div class="recipient-shell">
      <header>
        <h2 id="announcement-recipient-title">选择收件人</h2>
        <button class="close" type="button" aria-label="关闭选择收件人" @click="close">×</button>
      </header>
      <div class="recipient-content">
        <div class="recipient-modes" role="group" aria-label="发送范围">
          <label
            ><input v-model="mode" type="radio" name="recipient-mode" value="all" />全体用户</label
          >
          <label>
            <input v-model="mode" type="radio" name="recipient-mode" value="selected" />指定用户
          </label>
        </div>
        <template v-if="mode === 'selected'">
          <div class="recipient-search">
            <span class="search-icon" aria-hidden="true"></span>
            <input
              v-model="keyword"
              type="text"
              maxlength="64"
              aria-label="搜索用户名"
              placeholder="搜索用户名"
              autocomplete="off"
            />
            <button
              v-if="keyword"
              class="close"
              type="button"
              aria-label="清空搜索"
              @click="keyword = ''"
            >
              ×
            </button>
          </div>
          <p v-if="loading" class="recipient-feedback" role="status">正在查找用户…</p>
          <div v-else-if="error" class="recipient-feedback" role="alert">
            {{ error }}
            <button type="button" class="text-button" @click="search(page)">重试</button>
          </div>
          <p v-else-if="!rows.length" class="recipient-feedback">没有找到用户</p>
          <ul v-else class="recipient-results">
            <li v-for="user in rows" :key="user.id">
              <label :class="{ checked: isSelected(user.id) }">
                <span class="recipient-avatar" aria-hidden="true">{{
                  Array.from(user.username)[0]
                }}</span>
                <span class="recipient-name">{{ user.username }}</span>
                <input
                  type="checkbox"
                  :checked="isSelected(user.id)"
                  :disabled="selected.length >= limit && !isSelected(user.id)"
                  @change="toggle(user)"
                />
              </label>
            </li>
          </ul>
          <div v-if="!loading && !error && (page > 1 || hasNext)" class="recipient-pages">
            <button
              type="button"
              class="text-button"
              :disabled="page <= 1"
              @click="search(page - 1)"
            >
              上一页
            </button>
            <button
              type="button"
              class="text-button"
              :disabled="!hasNext"
              @click="search(page + 1)"
            >
              下一页
            </button>
          </div>
          <div v-if="selected.length" class="recipient-selection">
            <span>已选用户</span>
            <div class="recipient-chips">
              <button
                v-for="user in selected"
                :key="user.id"
                type="button"
                :aria-label="`移除 ${user.username}`"
                @click="remove(user.id)"
              >
                {{ user.username }} <span aria-hidden="true">×</span>
              </button>
            </div>
          </div>
          <p v-if="selected.length >= limit" class="recipient-feedback" role="status">
            已达到单次发布的收件人上限。
          </p>
        </template>
      </div>
      <footer>
        <button type="button" @click="close">取消</button>
        <button
          class="primary"
          type="button"
          :disabled="mode === 'selected' && !selected.length"
          @click="save"
        >
          确定
        </button>
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.recipient-dialog {
  box-sizing: border-box;
  width: min(470px, calc(100% - 32px));
  max-height: 85vh;
  max-height: 85dvh;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
  color: var(--admin-text);
  background: var(--admin-surface);
  box-shadow: 0 12px 36px var(--admin-shadow);
}
.recipient-dialog::backdrop {
  background: var(--admin-backdrop);
}
.recipient-shell {
  display: flex;
  flex-direction: column;
  max-height: calc(85vh - 2px);
  max-height: calc(85dvh - 2px);
}
header,
footer {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  padding: 12px 20px;
}
header {
  justify-content: space-between;
  padding-top: 17px;
  padding-bottom: 6px;
}
header h2 {
  margin: 0;
  font-size: 19px;
}
footer {
  justify-content: flex-end;
  border-top: 1px solid var(--admin-divider);
}
button {
  border: 0;
  border-radius: 7px;
  padding: 8px 13px;
  color: var(--admin-text);
  background: transparent;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.55;
  cursor: default;
}
.primary {
  color: var(--admin-surface);
  background: var(--admin-primary);
  font-weight: 600;
}
.close {
  padding: 2px 7px;
  color: var(--admin-muted);
  font-size: 21px;
  line-height: 1.2;
}
.close:hover {
  background: var(--admin-surface-hover);
}
.recipient-content {
  padding: 8px 20px 12px;
  min-height: 0;
  overflow: auto;
}
.recipient-modes {
  display: flex;
  flex-wrap: wrap;
  gap: 14px 20px;
  font-size: 14px;
}
.recipient-modes label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.recipient-modes input,
.recipient-results input {
  margin: 0;
  width: 16px;
  height: 16px;
  flex: none;
}
.recipient-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 9px 0 12px;
  margin-top: 14px;
  border: 1px solid var(--admin-border);
  border-radius: 7px;
}
.search-icon {
  position: relative;
  width: 9px;
  height: 9px;
  flex: none;
  border: 1px solid var(--admin-muted);
  border-radius: 50%;
}
.search-icon::after {
  position: absolute;
  right: -4px;
  bottom: -2px;
  width: 5px;
  height: 1px;
  transform: rotate(45deg);
  background: var(--admin-muted);
  content: '';
}
.recipient-search input {
  flex: 1;
  min-width: 0;
  height: 39px;
  border: 0;
  background: transparent;
  color: var(--admin-text);
  font: inherit;
  font-size: 16px;
}
.recipient-results {
  padding: 0 3px 0 0;
  margin: 8px 0 0;
  max-height: 221px;
  list-style: none;
  overflow: auto;
}
.recipient-results label {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  min-height: 43px;
  padding: 5px 8px;
  border-radius: 7px;
  font-size: 14px;
  cursor: pointer;
}
.recipient-results label:hover {
  background: var(--admin-surface-muted);
}
.recipient-results label.checked {
  background: var(--admin-accent-subtle);
}
.recipient-avatar {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 50%;
  color: var(--admin-secondary);
  background: var(--admin-surface-hover);
  font-size: 12px;
  overflow: hidden;
}
.recipient-name,
.recipient-chips button {
  overflow-wrap: anywhere;
}
.recipient-feedback {
  margin: 12px 0 4px;
  color: var(--admin-secondary);
  font-size: 13px;
}
.recipient-pages {
  display: flex;
  justify-content: flex-end;
}
.text-button {
  color: var(--admin-accent-text);
  padding: 5px 8px;
  font-size: 13px;
}
.recipient-selection {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding-top: 10px;
  margin-top: 9px;
  border-top: 1px solid var(--admin-divider);
  color: var(--admin-muted);
  font-size: 12px;
}
.recipient-selection > span {
  flex: none;
}
.recipient-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 5px;
  min-width: 0;
  max-height: 84px;
  overflow: auto;
}
.recipient-chips button {
  max-width: 100%;
  padding: 3px 7px;
  background: var(--admin-surface-hover);
  font-size: 12px;
  text-align: left;
}
.recipient-chips span {
  color: var(--admin-muted);
}
</style>
