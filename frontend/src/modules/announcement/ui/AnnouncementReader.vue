<script setup lang="ts">
import { onBeforeUnmount, ref, watch, useTemplateRef, onMounted } from 'vue'
import { isAxiosError } from 'axios'
import { authToken } from '@/shared/api/authSession'
import { fetchAnnouncement, type AnnouncementDetail } from '../api/announcementApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { notificationRevision } from '@/modules/message/model/useRealtimeUnread'
const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = useTemplateRef('dialog')
const detail = ref<AnnouncementDetail | null>(null),
  error = ref(''),
  loading = ref(false)
let version = 0,
  controller: globalThis.AbortController | undefined
/** 重查时先移除旧正文；撤回、换号及迟到结果不能继续显示旧内容。 */
async function load() {
  const token = authToken.value,
    id = props.id,
    ticket = ++version
  controller?.abort()
  controller = new globalThis.AbortController()
  detail.value = null
  error.value = ''
  if (!token) {
    emit('close')
    return
  }
  loading.value = true
  try {
    const result = await fetchAnnouncement(id, token, controller.signal)
    if (ticket === version && token === authToken.value && props.id === id) detail.value = result
  } catch (reason) {
    if (ticket === version && token === authToken.value)
      error.value =
        isAxiosError(reason) && reason.response?.status === 404
          ? '公告已撤回或不可访问。'
          : toErrorMessage(reason)
  } finally {
    if (ticket === version) loading.value = false
  }
}
onMounted(() => dialog.value?.showModal())
watch(
  () => props.id,
  () => void load(),
  { immediate: true },
)
watch(
  authToken,
  () => {
    version++
    controller?.abort()
    detail.value = null
    emit('close')
  },
  { flush: 'sync' },
)
watch(notificationRevision, () => void load())
onBeforeUnmount(() => {
  version++
  controller?.abort()
  dialog.value?.close()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="announcement-reader content-dialog"
    aria-labelledby="announcement-reader-heading"
    @cancel.prevent="$emit('close')"
  >
    <header class="reader-header">
      <div class="reader-heading">
        <h2 id="announcement-reader-heading">{{ detail?.title || '公告详情' }}</h2>
        <p class="reader-meta">
          <span class="reader-tag">系统公告</span>
          <template v-if="detail">
            <span aria-hidden="true">·</span>
            <time :datetime="detail.publishedAt">{{
              new Date(detail.publishedAt).toLocaleString('zh-CN', {
                year: 'numeric',
                month: 'numeric',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
                hour12: false,
              })
            }}</time>
          </template>
        </p>
      </div>
      <button
        type="button"
        class="detail-dialog-close"
        aria-label="关闭公告"
        @click="$emit('close')"
      >
        ×
      </button>
    </header>
    <p v-if="loading" class="detail-dialog-state" role="status">正在加载公告…</p>
    <div v-else-if="error" class="detail-dialog-state" role="alert">
      <p>{{ error }}</p>
      <button type="button" class="dialog-secondary" @click="load">重新读取</button>
    </div>
    <p v-else-if="detail" class="reader-content">{{ detail.content }}</p>
  </dialog>
</template>
<style scoped src="../../../shared/styles/content-dialog.css"></style>
<style scoped>
.announcement-reader.content-dialog {
  width: min(460px, calc(100% - 32px));
  padding: 22px 24px 24px;
  border-radius: 14px;
}
.announcement-reader[open] {
  display: flex;
  flex-direction: column;
}
.reader-header {
  display: flex;
  flex: none;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.reader-heading {
  min-width: 0;
}
.reader-heading h2 {
  margin: 0;
  color: #283443;
  font-size: 23px;
  line-height: 1.35;
}
.reader-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  margin: 5px 0 0;
  color: #647285;
  font-size: 12px;
}
.reader-tag {
  color: #96501a;
}
.announcement-reader .detail-dialog-close {
  width: 34px;
  height: 34px;
  border-radius: 7px;
  background: #f4f6f8;
  color: #647285;
  font-size: 22px;
}
.announcement-reader .detail-dialog-close:hover {
  background: #eaedf1;
}
.announcement-reader .detail-dialog-state {
  margin-top: 15px;
}
.reader-content {
  min-height: 0;
  overflow: auto;
  margin: 15px 0 0;
  padding: 11px 13px;
  border: 1px solid #e7ebef;
  border-radius: 8px;
  background: #fafbfc;
  white-space: pre-wrap;
  color: #38485b;
  font-size: 15px;
  line-height: 1.72;
}
@media (max-width: 500px) {
  .announcement-reader.content-dialog {
    padding: 18px 16px 19px;
  }
  .reader-heading h2 {
    font-size: 21px;
  }
}
@media (pointer: coarse) {
  .announcement-reader .detail-dialog-close {
    width: 44px;
    height: 44px;
  }
}
@media (max-height: 500px) {
  .announcement-reader[open] {
    display: block;
  }
}
</style>
