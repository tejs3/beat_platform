<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import * as api from '@/api/beat'

  const loading = ref(false)
  const parcels = ref<any[]>([])

  const load = async () => {
    loading.value = true
    try {
      const p = await api.listParcels()
      parcels.value = Array.isArray(p) ? p : []
    } catch {
      message.error('Failed to load parcels')
    } finally {
      loading.value = false
    }
  }

  onMounted(load)
</script>

<template>
  <div style="padding: 8px 4px">
    <a-typography-title :level="4" style="margin: 0 0 8px">Parcels</a-typography-title>
    <a-typography-text type="secondary">
      Activated package tarballs available to this cluster. Security (AutoTLS, LDAP, Kerberos) is under
      System → Security.
    </a-typography-text>
    <a-spin :spinning="loading" style="margin-top: 16px; display: block">
      <a-table :data-source="parcels" :pagination="false" row-key="name" size="small">
        <a-table-column title="Parcel" data-index="name" />
        <a-table-column title="Bytes" data-index="bytes" />
        <a-table-column title="SHA-256" data-index="sha256" ellipsis />
        <a-table-column title="Activated" data-index="activated" />
      </a-table>
    </a-spin>
  </div>
</template>
