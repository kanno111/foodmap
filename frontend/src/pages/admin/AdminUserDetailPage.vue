<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import AdminUserDetailWorkspace from '@/modules/admin/ui/AdminUserDetailWorkspace.vue'

defineProps<{ userId: string }>()
const auth = useLoginStore()
</script>

<template>
  <AdminUserDetailWorkspace
    :user-id="userId"
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    @logout="auth.signOut"
  />
</template>
