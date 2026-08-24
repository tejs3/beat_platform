<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import * as api from '@/api/beat'

  const emit = defineEmits<{ done: []; cancel: [] }>()

  const step = ref(0)
  const running = ref(false)
  const result = ref<any>(null)

  const form = reactive({
    regenerateCa: false,
    agentTlsDir: '/etc/beat/tls',
    scope: 'current' as 'current' | 'future',
    distributeAgents: true,
    enableManagerServer: true,
    enableManagerUi: true,
    enableAgentGrpc: true,
    enableServices: true
  })

  const steps = [
    { title: 'Generate CA', key: 'ca' },
    { title: 'TLS scope & targets', key: 'scope' },
    { title: 'Distribute', key: 'dist' },
    { title: 'Review & finish', key: 'finish' }
  ]

  const canNext = computed(() => {
    if (step.value === 0) return !!form.agentTlsDir.trim()
    if (step.value === 1) return form.distributeAgents || form.enableServices
    if (step.value === 2) return form.distributeAgents
    return true
  })

  const onCancel = () => emit('cancel')

  const onBack = () => {
    if (step.value > 0) step.value -= 1
  }

  const onNext = async () => {
    if (step.value < steps.length - 1) {
      step.value += 1
      return
    }
    await runWizard()
  }

  const runWizard = async () => {
    running.value = true
    result.value = null
    try {
      if (form.regenerateCa) {
        // force re-issue by calling init (existing PEMs skipped server-side unless missing)
        await api.initTls()
      }
      if (form.distributeAgents) {
        const r = await api.distributeTls({
          agentTlsDir: form.agentTlsDir.trim(),
          scope: form.scope,
          regenerateCa: form.regenerateCa,
          targets: {
            agents: form.distributeAgents,
            managerServer: form.enableManagerServer,
            managerUi: form.enableManagerUi,
            agentGrpc: form.enableAgentGrpc,
            services: form.enableServices
          }
        })
        result.value = r
        const ok = r?.distributedOk ?? 0
        const fail = r?.distributedFail ?? 0
        if (fail === 0 && ok > 0) {
          message.success(`Certificates distributed to ${ok} host(s)`)
        } else {
          message.warning(`Distribute finished: ${ok} ok, ${fail} failed`)
        }
      } else {
        result.value = await api.initTls()
        message.success('CA issued (no agent distribute selected)')
      }
      emit('done')
    } catch (e: any) {
      const detail =
        e?.response?.data?.message ||
        e?.response?.data?.data?.message ||
        e?.message ||
        'AutoTLS wizard failed'
      message.error(String(detail))
    } finally {
      running.value = false
    }
  }
</script>

<template>
  <div class="autotls-wizard">
    <a-row :gutter="24">
      <a-col :span="5">
        <a-steps direction="vertical" :current="step" size="small">
          <a-step v-for="s in steps" :key="s.key" :title="s.title" />
        </a-steps>
      </a-col>
      <a-col :span="19">
        <!-- Step 1: Generate CA -->
        <div v-show="step === 0" class="wizard-panel">
          <a-typography-title :level="4">Generate CA</a-typography-title>
          <a-typography-paragraph type="secondary">
            This wizard helps you generate (or rotate) TLS certificates and place them on cluster hosts via BEAT
            agents.
          </a-typography-paragraph>
          <a-alert
            type="info"
            show-icon
            style="margin-bottom: 16px"
            message="If a CA already exists, leaving regenerate off keeps it and only (re)issues missing host certs. Regenerate replaces the lab CA used for new host PEMs."
          />
          <a-form layout="vertical" style="max-width: 520px">
            <a-form-item label="Agent certificate directory">
              <a-input v-model:value="form.agentTlsDir" placeholder="/etc/beat/tls" />
            </a-form-item>
            <a-form-item label="Certificate authority">
              <a-radio-group v-model:value="form.regenerateCa">
                <a-radio :value="false">Keep existing CA (issue missing host certs only)</a-radio>
                <a-radio :value="true">Regenerate internal BEAT CA</a-radio>
              </a-radio-group>
            </a-form-item>
          </a-form>
        </div>

        <!-- Step 2: Scope & targets -->
        <div v-show="step === 1" class="wizard-panel">
          <a-typography-title :level="4">TLS scope &amp; targets</a-typography-title>
          <a-typography-paragraph type="secondary">
            Like BEAT AutoTLS — enable TLS across Manager, UI, agents, and cluster services.
          </a-typography-paragraph>
          <a-form layout="vertical" style="max-width: 560px">
            <a-form-item label="Enable TLS scope">
              <a-radio-group v-model:value="form.scope">
                <a-radio value="current">All existing cluster hosts</a-radio>
                <a-radio value="future">Existing and future hosts</a-radio>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="Enable TLS on">
              <a-checkbox v-model:checked="form.distributeAgents">
                Agents — install certificates + keystores under /etc/beat/tls
              </a-checkbox>
              <br />
              <a-checkbox v-model:checked="form.enableManagerServer">
                BEAT Manager server (HTTPS API)
              </a-checkbox>
              <br />
              <a-checkbox v-model:checked="form.enableManagerUi">
                BEAT Manager UI (same HTTPS endpoint)
              </a-checkbox>
              <br />
              <a-checkbox v-model:checked="form.enableAgentGrpc">
                Agent identity (certs on every agent host)
              </a-checkbox>
              <br />
              <a-checkbox v-model:checked="form.enableServices">
                Cluster services (Hadoop SSL + HTTP_AND_HTTPS + Apply Config)
              </a-checkbox>
            </a-form-item>
            <a-alert
              type="warning"
              show-icon
              message="Finish enables TLS for the selected targets. Plain HTTP :8080 is turned OFF. Use HTTPS :8083 only until you Disable AutoTLS."
            />
          </a-form>
        </div>

        <!-- Step 3: Distribute -->
        <div v-show="step === 2" class="wizard-panel">
          <a-typography-title :level="4">Distribute</a-typography-title>
          <a-typography-paragraph type="secondary">
            BEAT Manager distributes certificates to hosts through agents (gRPC) — not Ansible, not a separate SSH
            password form.
          </a-typography-paragraph>
          <a-descriptions bordered size="small" :column="1" style="max-width: 560px">
            <a-descriptions-item label="Agent directory">{{ form.agentTlsDir }}</a-descriptions-item>
            <a-descriptions-item label="Regenerate CA">{{ form.regenerateCa ? 'yes' : 'no' }}</a-descriptions-item>
            <a-descriptions-item label="Scope">{{
              form.scope === 'current' ? 'Current cluster' : 'Current + future'
            }}</a-descriptions-item>
            <a-descriptions-item label="Distribute to agents">{{
              form.distributeAgents ? 'yes' : 'no'
            }}</a-descriptions-item>
          </a-descriptions>
          <a-alert
            type="info"
            show-icon
            style="margin-top: 16px; max-width: 560px"
            message="Click Next to review, then Finish to run Issue + Distribute."
          />
        </div>

        <!-- Step 4: Review -->
        <div v-show="step === 3" class="wizard-panel">
          <a-typography-title :level="4">Review &amp; finish</a-typography-title>
          <a-typography-paragraph>
            Click <strong>Finish</strong> to execute with the options above. Nothing runs until you finish.
          </a-typography-paragraph>
          <a-descriptions bordered size="small" :column="1" style="max-width: 560px; margin-bottom: 16px">
            <a-descriptions-item label="Manager server TLS">{{
              form.enableManagerServer ? 'ENABLE' : 'no'
            }}</a-descriptions-item>
            <a-descriptions-item label="Manager UI TLS">{{
              form.enableManagerUi ? 'ENABLE' : 'no'
            }}</a-descriptions-item>
            <a-descriptions-item label="Agent identity">{{
              form.enableAgentGrpc ? 'ENABLE' : 'no'
            }}</a-descriptions-item>
            <a-descriptions-item label="Service TLS">{{
              form.enableServices ? 'ENABLE (Hadoop SSL + Configure)' : 'no'
            }}</a-descriptions-item>
          </a-descriptions>
          <a-alert
            v-if="result?.enabled"
            type="success"
            show-icon
            style="margin-bottom: 12px"
            :message="`Enabled — plain HTTP :8080 is OFF. Open https://10.1.0.191:${result.enabled?.managerHttps?.httpsPort || result.httpsPort || 8083}/ui/`"
          />
          <a-spin :spinning="running">
            <a-table
              v-if="result?.hosts"
              :data-source="result.hosts"
              :pagination="false"
              row-key="hostname"
              size="small"
            >
              <a-table-column title="Host" data-index="hostname" />
              <a-table-column title="OK" data-index="ok" :width="80">
                <template #default="{ text }">{{ text ? 'yes' : 'no' }}</template>
              </a-table-column>
              <a-table-column title="Message" data-index="message" />
            </a-table>
          </a-spin>
        </div>

        <div class="wizard-footer">
          <a-button type="link" @click="onCancel">Cancel</a-button>
          <a-space>
            <a-button :disabled="step === 0 || running" @click="onBack">Back</a-button>
            <a-button type="primary" :disabled="!canNext" :loading="running" @click="onNext">
              {{ step === steps.length - 1 ? 'Finish' : 'Next' }}
            </a-button>
          </a-space>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<style scoped lang="scss">
  .autotls-wizard {
    background: #fff;
    padding: 16px 8px 8px;
    min-height: 420px;
  }
  .wizard-panel {
    min-height: 320px;
  }
  .wizard-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 24px;
    padding-top: 16px;
    border-top: 1px solid #f0f0f0;
  }
</style>
