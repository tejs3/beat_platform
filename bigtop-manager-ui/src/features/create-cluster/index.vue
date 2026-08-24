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
  import { useMenuStore } from '@/store/menu'
  import { message, Modal } from 'ant-design-vue'
  import { getInstalledStatus, installDependencies } from '@/api/host'
  import { execCommand } from '@/api/command'

  import ClusterBase from './components/cluster-base.vue'
  import HostManage from './components/host-manage.vue'
  import ParcelStep from './components/parcel-step.vue'
  import CheckWorkflow from './components/check-workflow.vue'

  import { type InstalledStatusVO, Status } from '@/api/host/types'
  import type { ClusterCommandReq, CommandRequest, CommandVO, HostReq } from '@/api/command/types'

  const { t } = useI18n()
  const { confirmModal } = useModal()
  const menuStore = useMenuStore()

  const compRef = ref<any>()
  const installing = ref(false)
  const stepData = ref<[Partial<ClusterCommandReq>, HostReq[], Record<string, unknown>, CommandVO]>([
    {},
    [],
    {},
    {} as CommandVO
  ])
  const commandRequest = ref<CommandRequest>({
    command: 'Add',
    commandLevel: 'cluster'
  })

  const installStatus = shallowRef<InstalledStatusVO[]>([])
  const components = shallowRef<any[]>([ClusterBase, HostManage, ParcelStep, CheckWorkflow])
  const isInstall = computed(() => current.value === 1)
  const currentStepItems = computed(() => stepData.value[1])
  const hasNoUnknownHosts = computed(() => currentStepItems.value.every((item) => item.status !== Status.Unknown))
  const allInstallSuccess = computed(
    () =>
      currentStepItems.value.length > 0 &&
      currentStepItems.value.every((item) => item.status === Status.Success) &&
      hasNoUnknownHosts.value
  )
  const getCompName = computed(() => components.value[current.value])
  const isDone = computed(() => {
    const state = stepData.value[3]?.state
    return state != null && ['Successful', 'Failed'].includes(state)
  })

  const steps = computed(() => [
    'cluster.cluster_info',
    'cluster.host_config',
    'cluster.parcel_info',
    'cluster.create'
  ])

  const { current, stepsLimit, previousStep, nextStep } = useSteps(steps.value)

  const updateData = (val: Partial<ClusterCommandReq> | HostReq[]) => {
    stepData.value[current.value] = val as any
  }

  const prepareNextStep = async () => {
    if (current.value === 0) {
      const check = await compRef.value.check()
      if (check) {
        nextStep()
      }
    } else if (current.value === 1) {
      if (!allInstallSuccess.value) {
        await resolveInstallDependencies()
      } else {
        nextStep()
      }
    } else if (current.value === 2) {
      const check = await compRef.value.check()
      if (check) {
        const isClusterExisting = await createCluster()
        if (isClusterExisting) {
          nextStep()
        }
      }
    } else {
      nextStep()
    }
  }

  const resolveInstallDependencies = async () => {
    const targetStepData = stepData.value[1]

    if (stepData.value[1].length === 0) {
      message.error(t('host.uninstallable'))
      return
    }
    try {
      installing.value = true
      stepData.value[1] = setInstallItemStatus(targetStepData, Status.Installing)

      const result = await installDependencies(stepData.value[1])
      if (result) {
        pollUntilInstalled()
      }
    } catch (error) {
      console.error('Error resolving dependencies:', error)
      installing.value = false
      stepData.value[1] = setInstallItemStatus(stepData.value[1], Status.Failed)
    }
  }

  const recordInstalledStatus = async () => {
    try {
      const data = await getInstalledStatus()
      installStatus.value = data
      stepData.value[1] = setInstallItemStatus(stepData.value[1], data)

      return data.every((item) => item.status != Status.Installing)
    } catch (error) {
      console.error('Error recording installation status:', error)
    }
  }

  const pollUntilInstalled = (interval: number = 1000): void => {
    let isInitialized = false
    let intervalId: NodeJS.Timeout

    const poll = async () => {
      if (!isInitialized) {
        stepData.value[1] = setInstallItemStatus(stepData.value[1], Status.Installing)
        isInitialized = true
      }
      const result = await recordInstalledStatus()
      if (result) {
        installing.value = false
        clearInterval(intervalId)
      }
    }

    intervalId = setInterval(poll, interval)
    poll()
  }

  const setInstallItemStatus = <T extends HostReq>(items: T[], status: T[] | string): T[] => {
    const mergedMap = new Map<string, T>()

    for (const item of items) {
      mergedMap.set(item.hostname, { ...item })
    }

    if (Array.isArray(status)) {
      for (const item of status) {
        const mergedMapItem = mergedMap.get(item.hostname)
        mergedMap.set(item.hostname, mergedMapItem ? { ...mergedMapItem, ...item } : { ...item })
      }
    } else {
      for (const [hostname, item] of mergedMap) {
        mergedMap.set(hostname, { ...item, status })
      }
    }

    return Array.from(mergedMap.values())
  }

  const createCluster = async () => {
    try {
      commandRequest.value.clusterCommand = stepData.value[0] as ClusterCommandReq
      commandRequest.value.clusterCommand.hosts = stepData.value[1]
      stepData.value[3] = await execCommand(commandRequest.value)
      return true
    } catch (error) {
      console.log('error :>> ', error)
      return false
    }
  }

  onBeforeRouteLeave((_to, _from, next) => {
    if (current.value === stepsLimit.value && !isDone.value) {
      confirmModal({
        tipText: t('common.exit_confirm'),
        content: h('div', { style: { paddingLeft: '36px' } }, t('common.installing_exit_confirm_content')),
        cancelText: t('common.no'),
        style: { top: '30vh' },
        icon: null,
        okText: t('common.yes'),
        onOk: () => {
          next()
          Modal.destroyAll()
        }
      })
      return
    }
    next()
  })
</script>

<template>
  <div class="cluster-create">
    <header-card>
      <div class="steps-wrp">
        <a-steps :current="current">
          <a-step v-for="step in steps" :key="step" :disabled="true">
            <template #title>
              <span>{{ t(step) }}</span>
            </template>
          </a-step>
        </a-steps>
      </div>
    </header-card>
    <main-card>
      <template v-for="stepItem in steps" :key="stepItem">
        <div v-show="steps[current] === stepItem" class="step-title">
          <h5>{{ t(stepItem) }}</h5>
        </div>
      </template>
      <component
        :is="getCompName"
        ref="compRef"
        :step-data="stepData[current]"
        :hosts="stepData[1]"
        @update-data="updateData"
      />
      <div class="step-action">
        <a-space>
          <a-button v-show="current != stepsLimit" @click="() => $router.go(-1)">{{ t('common.exit') }}</a-button>
          <a-button v-show="current > 0 && current < stepsLimit" type="primary" @click="previousStep">
            {{ t('common.prev') }}
          </a-button>
          <template v-if="current >= 0 && current <= stepsLimit - 1">
            <a-button
              v-if="isInstall && !allInstallSuccess"
              type="primary"
              :disabled="installing"
              @click="prepareNextStep"
            >
              {{ t('cluster.install_dependencies') }}
            </a-button>
            <a-button v-else type="primary" @click="prepareNextStep">
              {{ t('common.next') }}
            </a-button>
          </template>
          <a-button v-show="current === stepsLimit && isDone" type="primary" @click="() => menuStore.updateSider()"
            >{{ t('common.done') }}
          </a-button>
        </a-space>
      </div>
    </main-card>
  </div>
</template>

<style lang="scss" scoped>
  .cluster-create {
    min-width: 600px;

    .header-card {
      min-height: 80px;
    }

    .steps-wrp {
      width: 100%;
      height: 100%;
      padding-inline: 6%;
    }
  }

  .step-title {
    h5 {
      margin: 0 0 12px;
      font-size: 16px;
      font-weight: 500;
    }
  }

  .step-action {
    text-align: end;
    margin-top: $space-md;
  }
</style>
