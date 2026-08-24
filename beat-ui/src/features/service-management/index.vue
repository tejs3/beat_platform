<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
-->

<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import { useServiceStore } from '@/store/service'
  import { useJobProgress } from '@/store/job-progress'
  import { Command, type CommandRequest } from '@/api/command/types'
  import { getServiceByProcess } from '@/api/service'

  import Overview from './overview.vue'
  import Components from './components.vue'
  import Configs from './configs.vue'

  import type { TabItem } from '@/components/base/main-card/types'
  import type { GroupItem } from '@/components/common/button-group/types'
  import type { ServiceVO } from '@/api/service/types'

  type Key = keyof typeof Command | 'Remove'

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const serviceStore = useServiceStore()
  const jobProgressStore = useJobProgress()
  const { activeTab } = useTabState(route.path, route.name === 'ServiceConfigProcess' ? '3' : '1')
  const { loading, serviceMap } = storeToRefs(serviceStore)

  const serviceDetail = shallowRef<ServiceVO>()
  const resolvedServiceId = ref<number>()
  const stepPages = shallowRef([Overview, Components, Configs])

  const getCompName = computed(() => stepPages.value[parseInt(activeTab.value) - 1])

  const clusterId = computed(() => Number(route.params.id))

  const componentPayload = computed(() => {
    const sid = resolvedServiceId.value ?? Number(route.params.serviceId)
    const serviceId = Number.isFinite(Number(sid)) ? Number(sid) : NaN
    return [clusterId.value, serviceId] as [number, number]
  })

  const tabs = computed((): TabItem[] => [
    { key: '1', title: t('common.overview') },
    { key: '2', title: t('common.component') },
    { key: '3', title: t('common.configs') }
  ])

  const actionGroup = computed<GroupItem[]>(() => [
    {
      shape: 'default',
      type: 'primary',
      text: t('common.operation'),
      dropdownMenu: [
        { action: 'Start', text: t('common.start', [t('common.service')]) },
        { action: 'Configure', text: t('service.apply_config') },
        { action: 'Restart', text: t('common.restart', [t('common.service')]) },
        { action: 'Stop', text: t('common.stop', [t('common.service')]) },
        { action: 'Remove', text: t('common.remove', [t('common.service')]), divider: true, danger: true }
      ],
      dropdownMenuClickEvent: (info) => dropdownMenuClick!(info)
    }
  ])

  const syncProcessUrl = (detail: ServiceVO | undefined) => {
    if (!detail?.processGeneration) return
    // Only rewrite when already on the CM-style process route — keep service-detail URLs
    // so Add Component / role links still have serviceId in the path.
    if (route.name !== 'ServiceConfigProcess') return
    const gen = String(detail.processGeneration)
    const wantPath = `/cluster-manage/clusters/${clusterId.value}/services/${gen}/config`
    if (route.path !== wantPath) {
      router.replace({
        name: 'ServiceConfigProcess',
        params: { id: String(clusterId.value), processId: gen }
      })
      activeTab.value = '3'
    }
  }

  const onServiceDeleted = (cid: number) => {
    router.replace({ path: `/cluster-manage/clusters/${cid}` })
  }

  const dropdownMenuClick: GroupItem['dropdownMenuClickEvent'] = async ({ key }) => {
    const [cid] = componentPayload.value
    // Prefer loaded detail — process URLs have no :serviceId so payload can be NaN
    const service = serviceDetail.value || serviceMap.value[cid]?.find((s) => s.id === resolvedServiceId.value)
    if (!service?.name || !Number.isFinite(Number(service.id))) {
      message.error(t('common.no_data'))
      return
    }
    const { name: serviceName, displayName } = service

    const processParams = {
      command: key as Key,
      clusterId: cid,
      commandLevel: 'service',
      serviceCommands: [{ serviceName, installed: true }]
    } as CommandRequest

    if (key === 'Remove') {
      serviceStore.removeService(service as ServiceVO, cid, () => onServiceDeleted(cid))
    } else {
      jobProgressStore.processCommand(processParams, getServiceDetail, { displayName })
    }
  }

  const getServiceDetail = async () => {
    try {
      loading.value = true
      const processKey = route.params.processId as string | undefined
      let detail: ServiceVO
      if (processKey) {
        detail = await getServiceByProcess(clusterId.value, processKey)
        resolvedServiceId.value = detail.id
      } else {
        const [, serviceId] = componentPayload.value
        if (!Number.isFinite(serviceId) || serviceId <= 0) {
          message.error(t('common.no_data'))
          return
        }
        detail = await serviceStore.getServiceDetail(clusterId.value, serviceId)
        resolvedServiceId.value = detail.id
      }
      serviceDetail.value = detail
      syncProcessUrl(detail)
    } catch (error) {
      console.log('error :>> ', error)
    } finally {
      loading.value = false
    }
  }

  provide('getServiceDetail', getServiceDetail)
  provide('resolvedServiceId', resolvedServiceId)

  onMounted(() => {
    getServiceDetail()
  })

  watch(
    () => route.params.processId,
    () => {
      if (route.name === 'ServiceConfigProcess') {
        getServiceDetail()
      }
    }
  )
</script>

<template>
  <a-spin :spinning="loading">
    <header-card
      :title="serviceDetail?.displayName || serviceDetail?.name"
      :avatar="serviceDetail?.name"
      :desc="serviceDetail?.desc"
      :action-groups="actionGroup"
    />
    <a-alert
      v-if="serviceDetail?.roleProcesses?.length"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="Process directories (one per role)"
    >
      <template #description>
        <div v-for="role in serviceDetail.roleProcesses" :key="role.componentName" class="role-process-line">
          {{ role.displayName || role.componentName }}: {{ role.processId }} → {{ role.processDir }}
        </div>
      </template>
    </a-alert>
    <a-alert
      v-else-if="serviceDetail?.processId"
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="Process"
      :description="`${serviceDetail.processId} → ${serviceDetail.processDir || ''}`"
    />
    <a-alert
      v-if="serviceDetail?.restartFlag"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
      :message="t('service.stale_config_title')"
      :description="t('service.stale_config_desc')"
    />
    <main-card v-model:active-key="activeTab" :tabs="tabs">
      <template #tab-item>
        <keep-alive>
          <component :is="getCompName" v-bind="{ ...serviceDetail }"></component>
        </keep-alive>
      </template>
    </main-card>
  </a-spin>
</template>

<style lang="scss" scoped>
  .role-process-line {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    line-height: 1.6;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
