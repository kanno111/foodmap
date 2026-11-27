<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import AdminInviteWorkspace from '@/modules/admin/ui/AdminInviteWorkspace.vue'

const auth = useLoginStore()
</script>

<template>
  <AdminInviteWorkspace
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  />
</template>
