<script setup lang="ts">
defineProps<{ alumni?: boolean; disabled: boolean; error?: string | null }>()
defineEmits<{ blur: [] }>()
const invite = defineModel<string>({ required: true })
</script>

<template>
  <div class="form-field">
    <label for="register-invite">{{ alumni ? '校友邀请码' : '邀请码（选填）' }}</label>
    <input
      id="register-invite"
      v-model="invite"
      type="text"
      maxlength="64"
      autocomplete="off"
      autocapitalize="characters"
      :spellcheck="false"
      :placeholder="alumni ? '请输入管理员发给你的邀请码' : '填写好友分享的邀请码'"
      :disabled="disabled"
      :aria-required="alumni || undefined"
      :aria-invalid="!!error"
      :aria-describedby="error ? 'register-invite-error' : undefined"
      @blur="$emit('blur')"
    />
    <p v-if="error" id="register-invite-error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
label {
  display: block;
  margin-bottom: 9px;
  font-size: 14px;
  font-weight: 800;
}
input {
  width: 100%;
  height: 50px;
  padding: 0 15px;
  border: 1px solid #e1e1dc;
  border-radius: 14px;
  color: #2b2a26;
  font: inherit;
}
</style>
