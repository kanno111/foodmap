<script setup lang="ts">
defineProps<{ alumni?: boolean; disabled: boolean; error?: string | null }>()
defineEmits<{ blur: [] }>()
const invite = defineModel<string>({ required: true })
</script>

<template>
  <div class="form-field">
    <div class="invite-field-heading">
      <label for="register-invite">{{ alumni ? '校友邀请码' : '邀请码（选填）' }}</label>
      <span v-if="alumni" id="register-invite-contact">（联系qq1010958435获取邀请码）</span>
    </div>
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
      :aria-describedby="
        [alumni && 'register-invite-contact', error && 'register-invite-error']
          .filter(Boolean)
          .join(' ') || undefined
      "
      @blur="$emit('blur')"
    />
    <p v-if="error" id="register-invite-error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.invite-field-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  margin-bottom: 9px;
}
label {
  font-size: 14px;
  font-weight: 800;
}
#register-invite-contact {
  color: #6e6a61;
  font-size: 13px;
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
