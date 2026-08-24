<!--
  CM-style role instance page: Status / Configuration / Processes / Logs + Start/Stop/Restart this role.
-->
<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import { getComponent } from '@/api/component'
  import { getServiceConfigs } from '@/api/service'
  import { fetchEvidenceLogs } from '@/api/beat'
  import { useJobProgress } from '@/store/job-progress'
  import { COMPONENT_STATUS, STATUS_COLOR } from '@/utils/constant'
  import { CommonStatus } from '@/enums/state'
  import type { ComponentVO } from '@/api/component/types'
  import type { CommandRequest } from '@/api/command/types'
  import type { ServiceConfig } from '@/api/service/types'

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const jobProgressStore = useJobProgress()

  const clusterId = computed(() => Number(route.params.id))
  const serviceId = computed(() => Number(route.params.serviceId))
  const componentId = computed(() => Number(route.params.componentId))

  const loading = ref(false)
  const role = ref<Partial<ComponentVO>>({})
  const activeTab = ref('status')
  const configs = ref<ServiceConfig[]>([])
  const configLoading = ref(false)
  const logText = ref('')
  const logLoading = ref(false)
  const configSearch = ref('')

  const statusLabel = computed(() => {
    const s = role.value.status
    if (s == null) return '-'
    const key = STATUS_COLOR[s as 1 | 2 | 3 | 4] || COMPONENT_STATUS[s]?.toLowerCase()
    return key ? t(`common.${key}`) : String(s)
  })
  const statusColor = computed(() => CommonStatus[STATUS_COLOR[role.value.status as 1 | 2 | 3 | 4]] || 'default')

  const flatProps = computed(() => {
    const rows: { file: string; name: string; value: string }[] = []
    for (const cfg of configs.value || []) {
      const props = (cfg as any).properties || []
      for (const p of props) {
        if (!p?.name) continue
        if (configSearch.value) {
          const q = configSearch.value.toLowerCase()
          if (!String(p.name).toLowerCase().includes(q) && !String(p.value ?? '').toLowerCase().includes(q)) {
            continue
          }
        }
        rows.push({ file: cfg.name || 'config', name: p.name, value: p.value ?? '' })
      }
    }
    return rows
  })

  const loadRole = async () => {
    loading.value = true
    try {
      role.value = await getComponent({ clusterId: clusterId.value, id: componentId.value })
    } catch (e) {
      message.error('Failed to load role instance')
    } finally {
      loading.value = false
    }
  }

  const loadConfigs = async () => {
    configLoading.value = true
    try {
      configs.value = await getServiceConfigs({ clusterId: clusterId.value, id: serviceId.value })
    } catch {
      configs.value = []
    } finally {
      configLoading.value = false
    }
  }

  const loadLogs = async () => {
    logLoading.value = true
    try {
      const svc = (role.value.serviceName || 'hadoop').toLowerCase()
      const data = await fetchEvidenceLogs({
        service: svc,
        lines: 120,
        hostname: role.value.hostname
      })
      // normalize evidence blob
      const hosts = (data as any)?.hosts || (data as any)?.data?.hosts || []
      if (Array.isArray(hosts) && hosts.length) {
        logText.value = hosts
          .map((h: any) => `=== ${h.hostname || h.host || 'host'} ===\n${h.content || h.log || h.text || JSON.stringify(h)}`)
          .join('\n\n')
      } else {
        logText.value = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
      }
    } catch (e: any) {
      logText.value = e?.message || 'Could not pull role logs'
    } finally {
      logLoading.value = false
    }
  }

  const runCommand = async (command: 'Start' | 'Stop' | 'Restart') => {
    if (!role.value.name || !role.value.hostname) return
    const req: CommandRequest = {
      command,
      commandLevel: 'component',
      clusterId: clusterId.value,
      componentCommands: [
        {
          componentName: role.value.name,
          hostnames: [role.value.hostname]
        }
      ]
    }
    jobProgressStore.processCommand(req, () => {
      message.success(`${command} submitted for ${role.value.displayName || role.value.name}`)
      setTimeout(loadRole, 4000)
    })
  }

  watch(activeTab, (tab) => {
    if (tab === 'config' && !configs.value.length) loadConfigs()
    if (tab === 'logs') loadLogs()
  })

  onMounted(async () => {
    await loadRole()
  })
</script>

<template>
  <div class="role-page">
    <a-page-header
      :title="`${role.displayName || role.name || 'Role'} (${role.hostname || ''})`"
      @back="router.push(`/cluster-manage/clusters/${clusterId}/service-detail/${serviceId}?tab=2`)"
    >
      <template #tags>
        <a-tag :color="statusColor">{{ statusLabel }}</a-tag>
      </template>
      <template #extra>
        <a-space>
          <a-button @click="runCommand('Start')">Start this role</a-button>
          <a-button @click="runCommand('Stop')">Stop this role</a-button>
          <a-button type="primary" @click="runCommand('Restart')">Restart this role</a-button>
        </a-space>
      </template>
    </a-page-header>

    <a-spin :spinning="loading">
      <a-tabs v-model:active-key="activeTab">
        <a-tab-pane key="status" tab="Status">
          <a-descriptions bordered :column="1" size="small">
            <a-descriptions-item label="Role">{{ role.displayName || role.name }}</a-descriptions-item>
            <a-descriptions-item label="Host">{{ role.hostname }}</a-descriptions-item>
            <a-descriptions-item label="Service">{{ role.serviceDisplayName || role.serviceName }}</a-descriptions-item>
            <a-descriptions-item label="Status">{{ statusLabel }}</a-descriptions-item>
            <a-descriptions-item label="Category">{{ role.category || '-' }}</a-descriptions-item>
            <a-descriptions-item v-if="role.quickLink" label="Web UI">
              <a-typography-link :href="role.quickLink.url" target="_blank">
                {{ role.quickLink.displayName }}
              </a-typography-link>
            </a-descriptions-item>
          </a-descriptions>
          <a-alert
            style="margin-top: 16px"
            type="info"
            show-icon
            message="Role-level actions affect only this instance (like CM). Process dirs are created per role start, not once for the whole service."
          />
        </a-tab-pane>

        <a-tab-pane key="config" tab="Configuration">
          <a-alert
            type="warning"
            show-icon
            style="margin-bottom: 12px"
            message="Configs are service-scoped today (shared by all roles). Role groups / per-host overrides are next — key/value list matches CM layout."
          />
          <a-input
            v-model:value="configSearch"
            allow-clear
            placeholder="Search property key or value"
            style="max-width: 420px; margin-bottom: 12px"
          />
          <a-spin :spinning="configLoading">
            <a-table
              size="small"
              :pagination="{ pageSize: 20 }"
              :data-source="flatProps"
              :row-key="(r: any) => `${r.file}:${r.name}`"
              :columns="[
                { title: 'File', dataIndex: 'file', width: 160 },
                { title: 'Property (key)', dataIndex: 'name' },
                { title: 'Value', dataIndex: 'value', ellipsis: true }
              ]"
            />
          </a-spin>
        </a-tab-pane>

        <a-tab-pane key="processes" tab="Processes">
          <a-descriptions bordered :column="1" size="small">
            <a-descriptions-item label="Host">{{ role.hostname }}</a-descriptions-item>
            <a-descriptions-item label="Role">{{ role.name }}</a-descriptions-item>
            <a-descriptions-item label="Process layout">
              Start/Restart creates
              <code>/var/run/beat-agent/process/&lt;generation&gt;-{{ role.serviceName }}-{{ role.name }}</code>
              on this host (role-scoped, not service-wide).
            </a-descriptions-item>
            <a-descriptions-item label="Tip">
              Use Start / Restart on this page so the process conf dir matches this role instance.
            </a-descriptions-item>
          </a-descriptions>
        </a-tab-pane>

        <a-tab-pane key="logs" tab="Logs">
          <a-space style="margin-bottom: 12px">
            <a-button type="primary" :loading="logLoading" @click="loadLogs">Refresh role logs</a-button>
          </a-space>
          <pre class="log-box">{{ logText || 'Click Refresh role logs' }}</pre>
        </a-tab-pane>
      </a-tabs>
    </a-spin>
  </div>
</template>

<style scoped lang="scss">
  .role-page {
    padding: 8px 4px 24px;
  }
  .log-box {
    max-height: 520px;
    overflow: auto;
    background: #0f172a;
    color: #e2e8f0;
    padding: 12px;
    border-radius: 8px;
    font-size: 12px;
    white-space: pre-wrap;
  }
</style>
