import { computed, ref } from 'vue'
import { emailError, fullEmailError } from './accountValidation'

/** 学生注册默认使用学校邮箱；邮箱登录与找回密码也支持完整地址。 */
export const CAMPUS_EMAIL_SUFFIX = '@std.uestc.edu.cn'

export function campusEmail(prefix: string): string {
  return `${prefix.trim()}${CAMPUS_EMAIL_SUFFIX}`.toLowerCase()
}

export function useAccountEmail() {
  const full = ref(false)
  const prefix = ref('')
  const complete = ref('')
  const input = computed({
    get: () => (full.value ? complete.value : prefix.value),
    set: (value: string) => {
      if (full.value) complete.value = value
      else prefix.value = value
    },
  })
  const address = computed(() =>
    full.value ? complete.value.trim().toLowerCase() : campusEmail(prefix.value),
  )

  function toggle() {
    if (!full.value && !complete.value && prefix.value.trim()) complete.value = address.value
    if (full.value && address.value.endsWith(CAMPUS_EMAIL_SUFFIX)) {
      prefix.value = address.value.slice(0, -CAMPUS_EMAIL_SUFFIX.length)
    }
    full.value = !full.value
  }

  function error() {
    return full.value ? fullEmailError(address.value) : emailError(address.value)
  }

  return { full, input, address, toggle, error }
}
