<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { AdminUserWorkspace } from '@/modules/admin'

const auth = useLoginStore()
</script>

<template>
  <AdminUserWorkspace
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  />
</template>
