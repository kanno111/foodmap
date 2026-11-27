<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { requestReferralCode } from '../api/referralCodeApi'

const auth = useLoginStore()
const code = ref<string | null>(null)
const action = ref<'read' | 'generate' | null>(null)
const readFailed = ref(false)
const error = ref('')
const copied = ref(false)
let activeRequest: globalThis.AbortController | undefined

async function request(next: 'read' | 'generate') {
  const token = authToken.value
  if (!auth.authenticated || !token || activeRequest) return

  const controller = new globalThis.AbortController()
  activeRequest = controller
  action.value = next
  error.value = ''
  try {
    const result = await requestReferralCode(next, token, controller.signal)
    if (controller.signal.aborted) return
    code.value = result
    readFailed.value = false
  } catch (reason) {
    if (controller.signal.aborted) return
    readFailed.value = next === 'read'
    error.value = toErrorMessage(reason)
  } finally {
    if (activeRequest === controller) {
      activeRequest = undefined
      action.value = null
    }
  }
}

async function copy() {
  const value = code.value
  const token = authToken.value
  if (!value) return
  copied.value = false
  error.value = ''
  try {
    await globalThis.navigator.clipboard.writeText(value)
    if (authToken.value === token && code.value === value) copied.value = true
  } catch {
    if (authToken.value === token && code.value === value) {
      error.value = '无法自动复制，请选中邀请码手动复制。'
    }
  }
}

// 凭证变更时立即清除旧邀请码，并忽略已离开页面或会话的请求结果。
watch(
  [() => auth.authenticated, () => auth.user?.id, authToken],
  () => {
    activeRequest?.abort()
    activeRequest = undefined
    action.value = null
    code.value = null
    error.value = ''
    readFailed.value = copied.value = false
    void request('read')
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => activeRequest?.abort())
</script>

<template>
  <div class="referral-code-setting">
    <div class="referral-code-row" :aria-busy="!!action">
      <span>我的邀请码</span>
      <span v-if="action === 'read'" class="referral-code-loading" role="status">加载中…</span>
      <div v-else class="referral-code-actions">
        <template v-if="code">
          <code>{{ code }}</code>
          <button type="button" class="referral-copy" @click="copy">
            {{ copied ? '已复制' : '复制' }}
          </button>
          <span class="referral-copy-status" role="status">{{ copied ? '邀请码已复制' : '' }}</span>
        </template>
        <button v-else-if="readFailed" type="button" @click="request('read')">重试</button>
        <button v-else type="button" :disabled="!!action" @click="request('generate')">
          {{ action === 'generate' ? '生成中…' : '生成邀请码' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="referral-code-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.referral-code-setting {
  border-top: 1px solid #edf0f3;
}
.referral-code-row {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 7px 0;
  font-size: 15px;
}
.referral-code-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.referral-code-actions code {
  color: #34404d;
  font:
    500 14px ui-monospace,
    Consolas,
    monospace;
  user-select: all;
}
.referral-code-actions button {
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid #cbd3dc;
  border-radius: 6px;
  background: #fff;
  color: #455464;
  font: inherit;
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
}
.referral-code-actions button:hover:not(:disabled) {
  background: #f6f7f9;
}
.referral-code-actions button:disabled {
  cursor: wait;
  opacity: 0.7;
}
.referral-copy {
  min-width: 61px;
}
.referral-code-loading,
.referral-code-error {
  font-size: 13px;
}
.referral-code-loading {
  color: #66717c;
}
.referral-code-error {
  margin: 0 0 8px;
  color: #a54439;
  text-align: right;
}
.referral-copy-status {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
</style>
