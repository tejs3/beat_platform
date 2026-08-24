<!--
  Cluster step 1: basic info only (parcels are configured in step 3).
-->
<script setup lang="ts">
  import { pick } from '@/utils/tools'

  import type { ClusterCommandReq } from '@/api/command/types'
  import type { FormItem } from '@/components/common/form-builder/types'

  type ClusterCommandReqKey = keyof ClusterCommandReq

  const { t } = useI18n()
  const props = defineProps<{ stepData: ClusterCommandReq }>()
  const emits = defineEmits(['updateData'])

  const activeKey = ref(['1', '2'])
  const autoFormRefMap = shallowRef<Map<string, Comp.FormBuilderInstance>>(new Map())

  const collapses = ref([
    {
      title: computed(() => t('cluster.base_info')),
      formValue: pick<ClusterCommandReq, ClusterCommandReqKey>(props.stepData, ['name', 'displayName', 'desc']),
      formItems: computed(() => baseInfoFormItems.value)
    },
    {
      title: computed(() => t('cluster.cluster_config')),
      formValue: pick<ClusterCommandReq, ClusterCommandReqKey>(props.stepData, ['rootDir', 'userGroup']),
      formItems: computed(() => clusterConfigFormItems.value)
    }
  ])

  const baseInfoFormItems = computed((): FormItem[] => [
    { type: 'input', field: 'name', label: t('cluster.name'), required: true },
    { type: 'input', field: 'displayName', label: t('cluster.display_name'), required: true },
    { type: 'textarea', field: 'desc', label: t('cluster.description'), required: true }
  ])

  const clusterConfigFormItems = computed((): FormItem[] => [
    { type: 'input', field: 'rootDir', label: t('cluster.root_dir'), required: true },
    { type: 'input', field: 'userGroup', label: t('cluster.user_group'), required: true }
  ])

  const getFormValues = () => {
    return collapses.value.reduce((pre, val) => {
      Object.assign(pre, val.formValue)
      return pre
    }, {})
  }

  const collectAutoFormRefs = (el: any, title: string) => {
    if (el) autoFormRefMap.value.set(title, el)
    else autoFormRefMap.value.delete(title)
  }

  const check = async () => {
    try {
      const autoFormRefs = [...autoFormRefMap.value.values()]
      const validation = await Promise.all(autoFormRefs.map((formRef) => formRef?.validate()))
      const allValid = validation.every((res) => res)
      autoFormRefs.forEach((_formRef, index) => {
        if (!validation[index] && !activeKey.value.includes(`${index + 1}`)) {
          activeKey.value.push(`${index + 1}`)
        }
      })
      return allValid
    } catch (error) {
      console.error('Error during form validation:', error)
      return false
    }
  }

  watch(
    () => [collapses.value[0].formValue, collapses.value[1].formValue],
    () => emits('updateData', getFormValues()),
    { deep: true }
  )

  defineExpose({ check })
</script>

<template>
  <div class="cluster-base">
    <a-collapse v-model:active-key="activeKey" :bordered="false" :ghost="true">
      <a-collapse-panel v-for="(collapse, idx) in collapses" :key="`${idx + 1}`" :header="collapse.title">
        <form-builder
          :key="collapse.title"
          :ref="(el) => collectAutoFormRefs(el, collapse.title)"
          v-model="collapse.formValue"
          :form-items="collapse.formItems"
          :span="14"
        />
      </a-collapse-panel>
    </a-collapse>
  </div>
</template>

<style lang="scss" scoped>
  .cluster-base {
    :deep(.ant-collapse-header) {
      background-color: $color-fill-quaternary;
    }
  }
</style>
