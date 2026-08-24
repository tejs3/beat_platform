<!--
  Parcels-only page: repo URL, BEAT .parcel lifecycle, services from active parcel.
-->
<script setup lang="ts">
  import { message, Modal } from 'ant-design-vue'
  import ServiceLogo from '@/components/common/service-logo/index.vue'
  import * as api from '@/api/beat'

  const { t } = useI18n()

  const parcelsLoading = ref(false)
  const servicesLoading = ref(false)
  const parcelActionLoading = ref(false)
  const parcels = ref<any[]>([])
  const parcelServices = ref<any[]>([])
  const parcelRepoUrl = ref('https://github.com/tejs3/beat-repo3.0.0-1')
  const repoError = ref('')
  const activeParcel = ref('')

  const activeParcelLabel = computed(() => {
    if (!activeParcel.value) return ''
    return activeParcel.value.replace('.parcel', '')
  })

  const loadParcels = async () => {
    parcelsLoading.value = true
    repoError.value = ''
    try {
      const [p, st] = await Promise.all([api.listParcels(), api.getParcelState().catch(() => null)])
      parcels.value = Array.isArray(p) ? p : []
      if (st) {
        if (st.repoUrl) parcelRepoUrl.value = st.repoUrl
        activeParcel.value = st.activeParcel || ''
        if (st.repoError) repoError.value = String(st.repoError)
      }
      await loadParcelServices()
    } catch {
      message.error('Failed to load parcels')
    } finally {
      parcelsLoading.value = false
    }
  }

  const loadParcelServices = async () => {
    servicesLoading.value = true
    try {
      const s = await api.listParcelServices()
      parcelServices.value = Array.isArray(s) ? s : []
    } catch {
      parcelServices.value = []
    } finally {
      servicesLoading.value = false
    }
  }

  const saveParcelRepoUrl = async () => {
    const url = parcelRepoUrl.value.trim()
    if (!url) {
      message.error('Enter a parcel repository URL')
      return
    }
    try {
      const st = await api.setParcelRepoUrl(url)
      if (st?.repoError) {
        repoError.value = String(st.repoError)
        message.error(st.repoError)
      } else {
        repoError.value = ''
        message.success('Parcel repository URL saved')
      }
      await loadParcels()
    } catch (e: any) {
      message.error(e?.message || 'Invalid parcel repository URL')
    }
  }

  const onActivate = async (row: any) => {
    parcelActionLoading.value = true
    try {
      await api.activateParcel(row.name)
      message.success(`Activated ${row.name}`)
      await loadParcels()
    } catch {
      message.error(`Activate failed for ${row.name}`)
    } finally {
      parcelActionLoading.value = false
    }
  }

  const onDeactivate = async (row: any) => {
    parcelActionLoading.value = true
    try {
      await api.deactivateParcel(row.name)
      message.success(`Deactivated ${row.name}`)
      await loadParcels()
    } catch {
      message.error(`Deactivate failed for ${row.name}`)
    } finally {
      parcelActionLoading.value = false
    }
  }

  const onRemove = (row: any) => {
    Modal.confirm({
      title: `Remove ${row.name}?`,
      content: 'Deletes the parcel file from the manager repo. Active parcels must be deactivated first.',
      okType: 'danger',
      onOk: async () => {
        parcelActionLoading.value = true
        try {
          await api.removeParcel(row.name)
          message.success(`Removed ${row.name}`)
          await loadParcels()
        } catch (e: any) {
          message.error(e?.message || e?.response?.data?.message || `Remove failed for ${row.name}`)
        } finally {
          parcelActionLoading.value = false
        }
      }
    })
  }

  onMounted(() => loadParcels())
</script>

<template>
  <div class="beat-parcels">
    <div class="menu-title">{{ t('menu.parcels') }}</div>
    <div class="parcel-repo-bar">
      <a-input
        v-model:value="parcelRepoUrl"
        placeholder="https://github.com/tejs3/beat-repo3.0.0-1"
        allow-clear
      />
      <a-button type="primary" @click="saveParcelRepoUrl">Save URL</a-button>
      <a-button @click="loadParcels">Refresh</a-button>
    </div>
    <a-alert
      v-if="repoError"
      type="error"
      show-icon
      :message="repoError"
      style="margin-bottom: 12px"
    />

    <a-spin :spinning="parcelsLoading || parcelActionLoading">
      <a-table :data-source="parcels" :pagination="false" row-key="name" size="small" style="margin-bottom: 24px">
        <a-table-column title="Parcel" data-index="name" />
        <a-table-column title="Bytes" data-index="bytes" />
        <a-table-column title="SHA-256" data-index="sha256" ellipsis />
        <a-table-column title="Status" data-index="activated">
          <template #default="{ record }">
            <a-tag :color="record.activated ? 'green' : 'default'">
              {{ record.activated ? 'Active' : 'Inactive' }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="Actions" key="actions" width="280px">
          <template #default="{ record }">
            <a-space>
              <a-button size="small" type="primary" :disabled="!!record.activated" @click="onActivate(record)">
                Activate
              </a-button>
              <a-button size="small" danger :disabled="!record.activated" @click="onDeactivate(record)">
                Deactivate
              </a-button>
              <a-button size="small" danger :disabled="!!record.activated" @click="onRemove(record)">
                Remove
              </a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </a-spin>

    <div class="sub-title">Services in active parcel</div>
    <a-spin :spinning="servicesLoading">
      <a-empty v-if="!activeParcel && parcelServices.length === 0" description="Activate a BEAT parcel to list services" />
      <a-row v-else :gutter="[16, 16]">
        <a-col v-for="svc in parcelServices" :key="svc.service || svc.name" :xs="12" :sm="8" :md="6" :lg="4">
          <a-card size="small" class="svc-card">
            <div class="svc-row">
              <ServiceLogo
                :name="svc.service || svc.name || ''"
                :repo-url="parcelRepoUrl"
                :size="52"
              />
              <div class="svc-meta">
                <div class="svc-name">{{ svc.displayName || svc.service || svc.name }}</div>
                <div class="svc-ver">{{ svc.version || activeParcelLabel }}</div>
              </div>
            </div>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>
  </div>
</template>

<style lang="scss" scoped>
  .beat-parcels {
    padding: 16px;
    background-color: #fff;
    .menu-title {
      font-size: 16px;
      font-weight: 500;
      margin-bottom: 12px;
    }
    .sub-title {
      font-size: 14px;
      font-weight: 500;
      margin-bottom: 12px;
    }
    .parcel-repo-bar {
      display: flex;
      gap: 8px;
      margin-bottom: 12px;
      align-items: center;
      :deep(.ant-input) {
        flex: 1;
      }
    }
    .svc-card {
      height: 100%;
    }
    .svc-card .svc-row {
      display: flex;
      gap: 12px;
      align-items: center;
    }
    .svc-meta {
      min-width: 0;
      flex: 1;
    }
    .svc-name {
      font-weight: 500;
      font-size: 14px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .svc-ver {
      font-size: 12px;
      color: #888;
    }
  }
</style>
