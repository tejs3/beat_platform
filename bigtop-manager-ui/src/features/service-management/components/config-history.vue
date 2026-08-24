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
  import {
    getServiceConfigSnapshotsList,
    recoveryServiceConfigSnapshot
  } from '@/api/service'

  import type { ServiceConfigSnapshot, ServiceParams, SnapshotRecovery } from '@/api/service/types'

  interface HistoryMeta {
    message?: string
    username?: string
    reverted?: boolean
    changes?: Array<{
      file?: string
      property?: string
      oldValue?: string
      newValue?: string
      description?: string
    }>
  }

  interface HistoryRow extends ServiceConfigSnapshot {
    message: string
    username: string
    reverted: boolean
    changes: NonNullable<HistoryMeta['changes']>
  }

  const emit = defineEmits<{ success: []; 'revert-and-apply': [] }>()
  const { t } = useI18n()

  const open = ref(false)
  const detailsOpen = ref(false)
  const loading = ref(false)
  const reverting = ref(false)
  const showReverted = ref(true)
  const search = ref('')
  const serviceInfo = shallowRef<ServiceParams>()
  const rows = ref<HistoryRow[]>([])
  const selected = shallowRef<HistoryRow>()

  const parseMeta = (snap: ServiceConfigSnapshot): HistoryMeta => {
    if (!snap.desc) return {}
    try {
      const parsed = JSON.parse(snap.desc)
      if (parsed && typeof parsed === 'object') return parsed as HistoryMeta
    } catch {
      // plain text desc from manual snapshots
    }
    return { message: snap.desc || snap.name, username: 'admin', changes: [] }
  }

  const filteredRows = computed(() => {
    let list = rows.value
    if (!showReverted.value) {
      list = list.filter((r) => !r.reverted && !String(r.message).startsWith('Reverted:'))
    }
    const q = search.value.trim().toLowerCase()
    if (q) {
      list = list.filter(
        (r) =>
          r.message.toLowerCase().includes(q) ||
          r.username.toLowerCase().includes(q) ||
          (r.createTime || '').toLowerCase().includes(q)
      )
    }
    return list
  })

  const load = async () => {
    if (!serviceInfo.value) return
    loading.value = true
    try {
      const data = await getServiceConfigSnapshotsList(serviceInfo.value)
      rows.value = (data || [])
        .filter((snap) => snap.desc?.trim().startsWith('{'))
        .map((snap) => {
        const meta = parseMeta(snap)
        return {
          ...snap,
          message: meta.message || snap.name || 'Configuration change',
          username: meta.username || 'admin',
          reverted: !!meta.reverted || String(snap.name || '').startsWith('Reverted:'),
          changes: meta.changes || []
        }
      })
    } catch (e) {
      console.log(e)
      rows.value = []
    } finally {
      loading.value = false
    }
  }

  const handleOpen = (data: ServiceParams) => {
    serviceInfo.value = data
    open.value = true
    load()
  }

  const openDetails = (row: HistoryRow) => {
    selected.value = row
    detailsOpen.value = true
  }

  const revertSelected = async (andApply: boolean) => {
    if (!selected.value?.id || !serviceInfo.value) return
    reverting.value = true
    try {
      await recoveryServiceConfigSnapshot({
        ...serviceInfo.value,
        snapshotId: selected.value.id
      } as SnapshotRecovery)
      message.success(t('service.revert_success'))
      detailsOpen.value = false
      open.value = false
      emit('success')
      if (andApply) {
        emit('revert-and-apply')
      }
    } catch (e) {
      message.error(t('service.revert_fail'))
      console.log(e)
    } finally {
      reverting.value = false
    }
  }

  defineExpose({ handleOpen })
</script>

<template>
  <a-drawer
    v-model:open="open"
    :title="t('service.config_history')"
    width="920"
    :destroy-on-close="true"
    :body-style="{ paddingBottom: '24px' }"
  >
    <p class="history-intro">
      {{ t('service.config_history_intro') }}
    </p>

    <div class="history-toolbar">
      <a-input
        v-model:value="search"
        allow-clear
        :placeholder="t('service.config_history_search')"
        style="max-width: 420px"
      />
      <a-space>
        <span>{{ t('service.show_reverted') }}</span>
        <a-switch v-model:checked="showReverted" />
      </a-space>
    </div>

    <a-spin :spinning="loading">
      <a-table
        :data-source="filteredRows"
        :pagination="{ pageSize: 10 }"
        row-key="id"
        size="middle"
      >
        <a-table-column :title="t('service.history_message')" key="message">
          <template #default="{ record }">
            <span :class="{ reverted: record.reverted }">{{ record.message }}</span>
          </template>
        </a-table-column>
        <a-table-column :title="t('common.operation')" key="details" width="100">
          <template #default="{ record }">
            <a @click="openDetails(record)">{{ t('service.details') }}</a>
          </template>
        </a-table-column>
        <a-table-column :title="t('common.create_time')" data-index="createTime" key="createTime" width="180" />
        <a-table-column :title="t('service.username')" key="username" width="120">
          <template #default="{ record }">
            {{ record.username }}
          </template>
        </a-table-column>
      </a-table>
    </a-spin>
  </a-drawer>

  <a-modal
    v-model:open="detailsOpen"
    :title="t('service.revision_details')"
    width="780"
    :destroy-on-close="true"
    :footer="null"
  >
    <template v-if="selected">
      <div class="meta">
        <div><b>{{ t('service.history_message') }}:</b> {{ selected.message }}</div>
        <div><b>{{ t('common.create_time') }}:</b> {{ selected.createTime }}</div>
        <div><b>{{ t('service.username') }}:</b> {{ selected.username }}</div>
      </div>

      <a-table
        :data-source="selected.changes"
        :pagination="false"
        size="small"
        row-key="property"
        style="margin-top: 16px"
      >
        <a-table-column :title="t('service.property')" data-index="property" key="property" width="200" />
        <a-table-column :title="t('service.value')" key="value">
          <template #default="{ record }">
            <pre class="diff">@@ -1,1 +1,1 @@
<span class="old">-{{ record.oldValue }}</span>
<span class="new">+{{ record.newValue }}</span></pre>
            <div v-if="record.file" class="file-tag">{{ record.file }}</div>
          </template>
        </a-table-column>
        <a-table-column :title="t('service.description')" key="description" width="220">
          <template #default="{ record }">
            {{ record.description || record.file || '—' }}
          </template>
        </a-table-column>
      </a-table>

      <div class="footer-actions">
        <a-button @click="detailsOpen = false">{{ t('common.cancel') }}</a-button>
        <a-button :loading="reverting" @click="revertSelected(false)">
          {{ t('service.revert_config_changes') }}
        </a-button>
        <a-button type="primary" danger :loading="reverting" @click="revertSelected(true)">
          {{ t('service.revert_and_apply') }}
        </a-button>
      </div>
    </template>
  </a-modal>
</template>

<style scoped lang="scss">
  .history-intro {
    color: rgba(0, 0, 0, 0.65);
    margin-bottom: 16px;
  }
  .history-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    margin-bottom: 16px;
  }
  .reverted {
    font-style: italic;
    color: rgba(0, 0, 0, 0.55);
  }
  .meta {
    display: flex;
    flex-direction: column;
    gap: 6px;
    line-height: 1.5;
  }
  .diff {
    margin: 0;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    background: #f6f8fa;
    padding: 8px;
    border-radius: 4px;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .old {
    display: block;
    background: #ffeef0;
    color: #b31d28;
  }
  .new {
    display: block;
    background: #e6ffed;
    color: #22863a;
  }
  .file-tag {
    margin-top: 4px;
    font-size: 12px;
    color: rgba(0, 0, 0, 0.45);
  }
  .footer-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 20px;
  }
</style>
