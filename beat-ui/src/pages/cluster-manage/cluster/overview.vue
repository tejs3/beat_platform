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
  import { usePngImage } from '@/composables/use-png-image'
  import { CommonStatus } from '@/enums/state'
  import { TIME_RANGES, STATUS_COLOR, POLLING_INTERVAL } from '@/utils/constant'

  import { useServiceStore } from '@/store/service'
  import { useJobProgress } from '@/store/job-progress'
  import { useTabStore } from '@/store/tab-state'

  import { Empty } from 'ant-design-vue'
  import { getClusterMetricsInfo } from '@/api/metrics'

  import type { ClusterStatusType, ClusterVO } from '@/api/cluster/types'
  import type { ServiceVO } from '@/api/service/types'
  import type { MetricsData, TimeRangeType } from '@/api/metrics/types'
  import type { CommandRequest } from '@/api/command/types'

  const props = defineProps<{ payload: ClusterVO }>()

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const tabStore = useTabStore()
  const jobProgressStore = useJobProgress()
  const serviceStore = useServiceStore()

  const isRunning = ref(false)
  const currTimeRange = ref<TimeRangeType>('5m')
  const clusterId = ref(Number(route.params.id))
  const chartData = ref<Partial<MetricsData>>({})

  const { services, loading: servicesLoading } = storeToRefs(serviceStore)
  const { payload } = toRefs(props)

  const clusterStatusColor = computed(
    () => CommonStatus[STATUS_COLOR[payload.value.status as ClusterStatusType]]
  )

  const serviceOperates = computed(() => ({
    Start: t('common.start', [t('common.service')]),
    Restart: t('common.restart', [t('common.service')]),
    Stop: t('common.stop', [t('common.service')])
  }))

  const noChartData = computed(
    () => !chartData.value?.memoryUsageCur && !(chartData.value?.memoryUsage && chartData.value.memoryUsage.length)
  )

  const diskIoLegend = computed<[string, string][]>(() => [
    ['diskRead', t('overview.disk_read')],
    ['diskWrite', t('overview.disk_write')]
  ])
  const networkIoLegend = computed<[string, string][]>(() => [
    ['networkRx', t('overview.network_rx')],
    ['networkTx', t('overview.network_tx')]
  ])
  const hdfsIoLegend = computed<[string, string][]>(() => [
    ['hdfsRead', t('overview.hdfs_read')],
    ['hdfsWrite', t('overview.hdfs_write')]
  ])

  const bpsFormatter = {
    yAxis: (value: unknown) => {
      const n = Number(value)
      if (!Number.isFinite(n)) return '--'
      if (Math.abs(n) >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB/s`
      if (Math.abs(n) >= 1024) return `${(n / 1024).toFixed(1)} KB/s`
      return `${n.toFixed(0)} B/s`
    },
    tooltip: (value: unknown) => {
      const n = Number(value)
      if (!Number.isFinite(n)) return '--'
      if (Math.abs(n) >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MB/s`
      if (Math.abs(n) >= 1024) return `${(n / 1024).toFixed(2)} KB/s`
      return `${n.toFixed(0)} B/s`
    }
  }

  const statusLabel = (status?: number) => {
    if (!status) return t('common.unknown')
    return t(`common.${STATUS_COLOR[status as ClusterStatusType]}`)
  }

  const handleServiceOperate = (item: any, service: ServiceVO) => {
    const { name, displayName } = service
    const { key: command } = item
    const params = {
      command,
      clusterId: clusterId.value,
      commandLevel: 'service',
      serviceCommands: [{ serviceName: name!, installed: true }]
    } as CommandRequest

    jobProgressStore.processCommand(params, undefined, { displayName })
  }

  const openService = (service: ServiceVO) => {
    if (service.processGeneration) {
      router.push({
        name: 'ServiceConfigProcess',
        params: { id: clusterId.value, processId: String(service.processGeneration) }
      })
      return
    }
    router.push({
      name: 'ServiceDetail',
      params: { id: clusterId.value, serviceId: service.id }
    })
  }

  const handleTimeRange = (time: TimeRangeType) => {
    if (currTimeRange.value === time) return
    currTimeRange.value = time
    getClusterMetrics()
    pause()
    resume()
  }

  const getClusterMetrics = async () => {
    if (isRunning.value) return
    isRunning.value = true
    try {
      chartData.value = await getClusterMetricsInfo({ id: clusterId.value }, { interval: currTimeRange.value })
    } catch (error) {
      console.log('Failed to fetch cluster metrics:', error)
    } finally {
      isRunning.value = false
    }
  }

  const loadServices = () => {
    if (clusterId.value != undefined) {
      serviceStore.getServices(clusterId.value)
    }
  }

  const { pause, resume } = useIntervalFn(getClusterMetrics, POLLING_INTERVAL, { immediate: true })

  onActivated(() => {
    const currTab = tabStore.getActiveTab(route.path) ?? '1'
    if (currTab != '1') return
    loadServices()
    getClusterMetrics()
    resume()
  })

  onDeactivated(() => {
    pause()
  })
</script>

<template>
  <div class="dashboard">
    <a-row :gutter="[50, 16]" :wrap="true">
      <a-col :xs="24" :sm="24" :md="24" :lg="10" :xl="7">
        <a-spin :spinning="servicesLoading">
          <a-descriptions layout="vertical" bordered :column="1">
            <template #title>
              <a-typography-text strong :content="t('overview.service_info')" />
            </template>
            <a-descriptions-item>
              <template #label>
                <div class="desc-sub-label">
                  <a-typography-text strong :content="t('overview.cluster_health')" />
                  <a-tag class="reset-tag" :color="clusterStatusColor">
                    <status-dot :color="clusterStatusColor" />
                    {{ statusLabel(payload.status) }}
                  </a-tag>
                </div>
              </template>
              <div v-if="!services.length" class="box-empty">
                <a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE" />
              </div>
              <div
                v-for="service in services"
                :key="service.id"
                class="service-item"
                @click="openService(service)"
              >
                <a-avatar v-if="service.name" :src="usePngImage(service.name.toLowerCase())" :size="22" />
                <div class="service-meta">
                  <a-typography-text :content="service.displayName" />
                  <div class="service-flags">
                    <a-tag :color="CommonStatus[STATUS_COLOR[service.status]]" class="svc-tag">
                      <status-dot :color="CommonStatus[STATUS_COLOR[service.status]]" />
                      {{ statusLabel(service.status) }}
                    </a-tag>
                    <a-tag :color="service.restartFlag ? 'error' : 'success'" class="svc-tag">
                      {{
                        service.restartFlag
                          ? t('overview.restart_required')
                          : t('overview.restart_ok')
                      }}
                    </a-tag>
                    <a-tag v-if="service.restartFlag" color="warning" class="svc-tag">
                      {{ t('overview.stale_configs') }}
                    </a-tag>
                  </div>
                </div>
                <a-dropdown :trigger="['click']" @click.stop>
                  <a-button type="text" shape="circle" size="small" @click.stop>
                    <svg-icon name="more" style="margin: 0" />
                  </a-button>
                  <template #overlay>
                    <a-menu @click="handleServiceOperate($event, service)">
                      <a-menu-item v-for="[operate, text] of Object.entries(serviceOperates)" :key="operate">
                        <span>{{ text }}</span>
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </a-descriptions-item>
          </a-descriptions>
        </a-spin>
      </a-col>
      <a-col :xs="24" :sm="24" :md="24" :lg="14" :xl="17">
        <div class="box-title">
          <a-typography-text strong :content="t('overview.chart')" />
          <a-space :size="12">
            <div
              v-for="time in TIME_RANGES"
              :key="time"
              tabindex="0"
              class="time-range"
              :class="{ 'time-range-activated': currTimeRange === time }"
              @click="handleTimeRange(time)"
            >
              {{ time }}
            </div>
          </a-space>
        </div>
        <template v-if="noChartData">
          <div class="box-empty">
            <a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE" />
          </div>
        </template>
        <a-row v-else class="box-content">
          <a-col :xs="24" :sm="24" :md="12" :lg="12" :xl="12">
            <div class="chart-item-wrp">
              <category-chart
                chart-id="chart3"
                :x-axis-data="chartData?.timestamps"
                :data="chartData?.memoryUsage ?? []"
                :title="t('overview.memory_usage')"
              />
            </div>
          </a-col>
          <a-col :xs="24" :sm="24" :md="12" :lg="12" :xl="12">
            <div class="chart-item-wrp">
              <category-chart
                chart-id="chart4"
                :x-axis-data="chartData?.timestamps"
                :data="chartData?.cpuUsage ?? []"
                :title="t('overview.cpu_usage')"
              />
            </div>
          </a-col>
          <a-col :xs="24" :sm="24" :md="12" :lg="12" :xl="12">
            <div class="chart-item-wrp">
              <category-chart
                chart-id="chart5"
                :x-axis-data="chartData?.timestamps"
                :data="chartData"
                :legend-map="diskIoLegend"
                :title="t('overview.cluster_disk_io')"
                :formatter="bpsFormatter"
              />
            </div>
          </a-col>
          <a-col :xs="24" :sm="24" :md="12" :lg="12" :xl="12">
            <div class="chart-item-wrp">
              <category-chart
                chart-id="chart6"
                :x-axis-data="chartData?.timestamps"
                :data="chartData"
                :legend-map="networkIoLegend"
                :title="t('overview.cluster_network_io')"
                :formatter="bpsFormatter"
              />
            </div>
          </a-col>
          <a-col :xs="24" :sm="24" :md="24" :lg="24" :xl="24">
            <div class="chart-item-wrp">
              <category-chart
                chart-id="chart7"
                :x-axis-data="chartData?.timestamps"
                :data="chartData"
                :legend-map="hdfsIoLegend"
                :title="t('overview.cluster_hdfs_io')"
                :formatter="bpsFormatter"
              />
            </div>
          </a-col>
        </a-row>
      </a-col>
    </a-row>
  </div>
</template>

<style lang="scss" scoped>
  :deep(.ant-avatar) {
    border-radius: 4px;
    img {
      object-fit: contain !important;
    }
  }

  .box {
    &-title {
      @include flexbox($justify: space-between);
      margin-bottom: 20px;
    }

    &-content {
      border-radius: 8px;
      overflow: visible;
      box-sizing: border-box;
      border: 1px solid $color-border;
    }

    &-empty {
      @include flexbox($justify: center, $align: center);
      min-height: 160px;
      border-radius: 8px;
      box-sizing: border-box;
      border: 1px solid $color-border;
      margin: 12px;
    }
  }

  .time-range {
    padding-inline: 6px;
    border-radius: 4px;
    text-align: center;
    cursor: pointer;
    user-select: none;
    outline: none;
    transition: background-color 0.3s;

    &:hover {
      color: $color-primary-text-hover;
    }

    &-activated {
      color: $color-primary-text;
    }
  }

  .service-item {
    display: grid;
    grid-template-columns: auto 1fr auto;
    gap: $space-md;
    align-items: center;
    padding: 12px 16px;
    border-top: 1px solid #f0f0f0;
    cursor: pointer;

    &:hover {
      background: rgba(26, 107, 138, 0.04);
    }
  }

  .service-meta {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }

  .service-flags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .svc-tag {
    margin: 0;
    @include flexbox($align: center, $gap: 4px);
  }

  .chart-item-wrp {
    border: 1px solid $color-border;
    margin-right: -1px;
    margin-bottom: -1px;
  }

  .dashboard {
    :deep(.ant-descriptions-view) {
      overflow: hidden;
      border-color: $color-border;

      .ant-descriptions-item-label {
        padding: 0;
      }

      .ant-descriptions-item-content {
        padding: 0;
      }
    }

    .desc-sub-label {
      @include flexbox($align: center, $justify: space-between, $gap: $space-sm);
      background-color: $color-border-secondary;
      padding: $space-md;

      :deep(.ant-tag) {
        @include flexbox($align: center, $gap: 4px);
        margin: 0;
      }
    }
  }
</style>
