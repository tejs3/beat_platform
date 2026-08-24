<!--
  Create Cluster step 3: repo URL, activate parcel, distribute to hosts from step 2.
-->
<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import * as beatApi from '@/api/beat'
  import ServiceLogo from '@/components/common/service-logo/index.vue'
  import type { HostReq } from '@/api/command/types'

  const { t } = useI18n()
  const props = defineProps<{ stepData: Record<string, unknown>; hosts: HostReq[] }>()
  const emits = defineEmits(['updateData'])

  const parcelRepoUrl = ref('https://github.com/tejs3/beat-repo3.0.0-1')
  const activeParcel = ref('')
  const parcels = ref<any[]>([])
  const parcelServices = ref<any[]>([])
  const parcelLoading = ref(false)
  const distributing = ref(false)
  const repoError = ref('')
  const distributeRows = ref<any[]>([])
  const distributed = ref(false)

  const hostnames = computed(() =>
    (props.hosts || [])
      .map((h) => String(h.hostname || '').trim())
      .filter(Boolean)
  )

  const loadParcels = async () => {
    parcelLoading.value = true
    repoError.value = ''
    try {
      const [p, st, svcs] = await Promise.all([
        beatApi.listParcels(),
        beatApi.getParcelState().catch(() => null),
        beatApi.listParcelServices().catch(() => [])
      ])
      parcels.value = Array.isArray(p) ? p : []
      if (st?.repoUrl) parcelRepoUrl.value = st.repoUrl
      activeParcel.value = st?.activeParcel || ''
      if (st?.repoError) repoError.value = String(st.repoError)
      parcelServices.value = Array.isArray(svcs) ? svcs : []
      distributed.value = st?.status === 'distributed'
      if (Array.isArray(st?.distributedHosts)) {
        distributeRows.value = st.distributedHosts.map((hn: string) => ({
          hostname: hn,
          ok: true,
          message: 'Previously distributed'
        }))
      }
    } finally {
      parcelLoading.value = false
    }
  }

  const saveParcelUrl = async () => {
    const url = parcelRepoUrl.value.trim()
    if (!url) {
      message.error('Enter a parcel repository URL')
      return false
    }
    try {
      const st = await beatApi.setParcelRepoUrl(url)
      if (st?.repoError) {
        repoError.value = String(st.repoError)
        message.error(st.repoError)
        return false
      }
      repoError.value = ''
      message.success('Parcel repository URL saved')
      await loadParcels()
      return true
    } catch {
      message.error('Failed to save parcel URL')
      return false
    }
  }

  const onActivate = async (row: any) => {
    parcelLoading.value = true
    try {
      await beatApi.activateParcel(row.name)
      message.success(`Activated ${row.name}`)
      distributed.value = false
      distributeRows.value = []
      await loadParcels()
    } catch {
      message.error(`Activate failed for ${row.name}`)
    } finally {
      parcelLoading.value = false
    }
  }

  const distributeToHosts = async () => {
    if (!activeParcel.value) {
      message.error(t('cluster.parcel_required'))
      return false
    }
    if (hostnames.value.length === 0) {
      message.error('Add at least one host before distributing parcels')
      return false
    }
    distributing.value = true
    try {
      const result = await beatApi.distributeParcel({
        hostnames: hostnames.value,
        parcelName: activeParcel.value
      })
      distributeRows.value = Array.isArray(result?.hosts) ? result.hosts : []
      distributed.value = !!result?.ok
      emits('updateData', { distributed: distributed.value, hosts: distributeRows.value })
      if (result?.ok) {
        message.success('Parcel distributed to all hosts')
        return true
      }
      message.error('Parcel distribution failed on one or more hosts')
      return false
    } catch {
      message.error('Parcel distribution failed')
      return false
    } finally {
      distributing.value = false
    }
  }

  const check = async () => {
    const url = parcelRepoUrl.value.trim()
    if (url) {
      const saved = await saveParcelUrl()
      if (!saved) return false
    }
    if (!activeParcel.value) {
      message.error(t('cluster.parcel_required'))
      return false
    }
    if (!distributed.value) {
      return distributeToHosts()
    }
    return true
  }

  onMounted(() => loadParcels())

  defineExpose({ check })
</script>

<template>
  <div class="parcel-step">
    <a-spin :spinning="parcelLoading || distributing">
      <div class="parcel-url-row">
        <a-input v-model:value="parcelRepoUrl" placeholder="https://github.com/tejs3/beat-repo3.0.0-1" />
        <a-button type="primary" @click="saveParcelUrl">Save URL</a-button>
        <a-button @click="loadParcels">Refresh</a-button>
      </div>
      <a-alert v-if="repoError" type="error" show-icon :message="repoError" style="margin-top: 8px" />

      <a-table
        :data-source="parcels"
        :pagination="false"
        row-key="name"
        size="small"
        style="margin-top: 16px"
      >
        <a-table-column title="Parcel" data-index="name" />
        <a-table-column title="Status">
          <template #default="{ record }">
            <a-tag :color="record.activated ? 'green' : 'default'">
              {{ record.activated ? 'Active' : 'Inactive' }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="Actions" key="actions" width="140px">
          <template #default="{ record }">
            <a-button size="small" type="primary" :disabled="!!record.activated" @click="onActivate(record)">
              Activate
            </a-button>
          </template>
        </a-table-column>
      </a-table>

      <div v-if="hostnames.length" class="host-block">
        <div class="sub-title">Distribute to cluster hosts ({{ hostnames.length }})</div>
        <a-button type="primary" :loading="distributing" :disabled="!activeParcel" @click="distributeToHosts">
          Download &amp; distribute parcel
        </a-button>
        <a-table
          v-if="distributeRows.length"
          :data-source="distributeRows"
          :pagination="false"
          row-key="hostname"
          size="small"
          style="margin-top: 12px"
        >
          <a-table-column title="Host" data-index="hostname" />
          <a-table-column title="Result">
            <template #default="{ record }">
              <a-tag :color="record.ok ? 'green' : 'red'">{{ record.ok ? 'OK' : 'Failed' }}</a-tag>
              <span v-if="record.message" class="dist-msg">{{ record.message }}</span>
            </template>
          </a-table-column>
        </a-table>
      </div>
      <a-alert
        v-else
        type="warning"
        show-icon
        message="No hosts configured"
        description="Go back to the Hosts step and add cluster hosts first."
        style="margin-top: 16px"
      />

      <div v-if="parcelServices.length" class="parcel-svc-grid">
        <div v-for="svc in parcelServices" :key="svc.service || svc.name" class="parcel-svc-chip">
          <ServiceLogo :name="svc.service || svc.name" :repo-url="parcelRepoUrl" :size="32" />
          <span>{{ svc.displayName || svc.service || svc.name }}</span>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<style lang="scss" scoped>
  .parcel-url-row {
    display: flex;
    gap: 8px;
    :deep(.ant-input) {
      flex: 1;
    }
  }
  .sub-title {
    margin: 16px 0 8px;
    font-weight: 500;
  }
  .host-block {
    margin-top: 8px;
  }
  .dist-msg {
    margin-left: 8px;
    font-size: 12px;
    color: #666;
  }
  .parcel-svc-grid {
    margin-top: 16px;
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .parcel-svc-chip {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 4px 10px 4px 4px;
    background: #fafafa;
    border-radius: 8px;
    font-size: 13px;
  }
</style>
