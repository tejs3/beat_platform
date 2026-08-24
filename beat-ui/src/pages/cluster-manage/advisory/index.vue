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
  import * as advisoryApi from '@/api/advisory'
  import { fetchEvidenceLogs } from '@/api/beat'
  import type { AdvisorySuggestion } from '@/api/advisory/types'

  const { t } = useI18n()
  const loading = ref(false)
  const evidenceLoading = ref(false)
  const suggestions = ref<AdvisorySuggestion[]>([])
  const evidenceOpen = ref(false)
  const evidenceText = ref('')
  const evidenceService = ref('hadoop')

  const severityColor = (severity: string) => {
    const s = (severity || '').toLowerCase()
    if (s === 'critical') return 'red'
    if (s === 'high') return 'orange'
    if (s === 'medium') return 'gold'
    return 'blue'
  }

  const load = async () => {
    loading.value = true
    try {
      const data = await advisoryApi.listSuggestions()
      suggestions.value = Array.isArray(data) ? data : []
    } catch (e) {
      message.error(t('advisory.load_failed'))
      suggestions.value = []
    } finally {
      loading.value = false
    }
  }

  const onFetchLogs = async (svc?: string) => {
    const service = (svc || evidenceService.value || 'hadoop').toLowerCase()
    evidenceService.value = service
    evidenceLoading.value = true
    evidenceOpen.value = true
    evidenceText.value = ''
    try {
      const data = await fetchEvidenceLogs({ service, lines: 120 })
      const parts: string[] = []
      parts.push(`service=${data?.service} — ${data?.note || ''}`)
      for (const h of data?.hosts || []) {
        parts.push(`\n### ${h.hostname} (heartbeat ${h.agentHeartbeat || '—'})`)
        if (h.excerpt) parts.push(h.excerpt)
        else parts.push(h.message || '(no log)')
      }
      evidenceText.value = parts.join('\n')
      message.success(t('advisory.fetch_logs_ok'))
    } catch {
      message.error(t('advisory.fetch_logs_fail'))
      evidenceText.value = t('advisory.fetch_logs_fail')
    } finally {
      evidenceLoading.value = false
    }
  }

  onMounted(load)
</script>

<template>
  <div class="advisory-page">
    <div class="advisory-header">
      <div>
        <a-typography-title :level="4" style="margin: 0">
          {{ t('advisory.title') }}
        </a-typography-title>
        <a-typography-text type="secondary">
          {{ t('advisory.subtitle') }}
        </a-typography-text>
      </div>
      <a-space>
        <a-select v-model:value="evidenceService" style="width: 140px">
          <a-select-option value="hadoop">hadoop</a-select-option>
          <a-select-option value="yarn">yarn</a-select-option>
          <a-select-option value="hbase">hbase</a-select-option>
          <a-select-option value="hive">hive</a-select-option>
          <a-select-option value="spark">spark</a-select-option>
          <a-select-option value="zookeeper">zookeeper</a-select-option>
        </a-select>
        <a-button :loading="evidenceLoading" @click="onFetchLogs()">
          {{ t('advisory.fetch_logs') }}
        </a-button>
        <a-button type="primary" :loading="loading" @click="load">
          {{ t('advisory.refresh') }}
        </a-button>
      </a-space>
    </div>

    <a-alert type="info" show-icon style="margin: 16px 0" :message="t('advisory.advise_only_banner')" />

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && suggestions.length === 0" :description="t('advisory.empty')" />
      <div v-if="!loading && suggestions.length > 0" class="card-list">
        <a-card v-for="item in suggestions" :key="item.id" class="suggestion-card" :title="item.problem">
          <template #extra>
            <a-space>
              <a-tag :color="severityColor(item.severity)">{{ item.severity }}</a-tag>
              <a-tag>{{ item.mode }}</a-tag>
              <a-tag color="green" v-if="item.advisoryOnly">{{ t('advisory.advise_only') }}</a-tag>
              <a-button size="small" @click="onFetchLogs(String(item.service || evidenceService))">
                {{ t('advisory.fetch_logs') }}
              </a-button>
            </a-space>
          </template>

          <p><strong>{{ t('advisory.service') }}:</strong> {{ item.service }}</p>
          <p><strong>{{ t('advisory.why') }}:</strong> {{ item.whyItMatters }}</p>
          <p><strong>{{ t('advisory.fix') }}:</strong></p>
          <pre class="fix-block">{{ item.suggestedFix }}</pre>
          <p><strong>{{ t('advisory.verify') }}:</strong> {{ item.howToVerify }}</p>
          <p class="muted">
            {{ t('advisory.confidence') }}: {{ item.confidence }} —
            {{ t('advisory.human_applies') }}
          </p>
        </a-card>
      </div>
    </a-spin>

    <a-modal v-model:open="evidenceOpen" :title="t('advisory.evidence')" :footer="null" width="900px">
      <a-spin :spinning="evidenceLoading">
        <pre class="fix-block">{{ evidenceText }}</pre>
      </a-spin>
    </a-modal>
  </div>
</template>

<style scoped lang="scss">
  .advisory-page {
    padding: 16px 20px;
  }

  .advisory-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 16px;
  }

  .card-list {
    display: grid;
    gap: 16px;
  }

  .suggestion-card {
    border-radius: 8px;
  }

  .fix-block {
    white-space: pre-wrap;
    background: #f5f5f5;
    padding: 12px;
    border-radius: 6px;
    margin: 0 0 12px;
    max-height: 480px;
    overflow: auto;
  }

  .muted {
    color: rgba(0, 0, 0, 0.45);
    margin-bottom: 0;
  }
</style>
