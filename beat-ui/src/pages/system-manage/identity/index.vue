<script setup lang="ts">
  import { message } from 'ant-design-vue'
  import * as api from '@/api/beat'
  import AutotlsWizard from '@/features/autotls-wizard/index.vue'

  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const tab = ref('autotls')
  const showWizard = ref(false)
  const tls = ref<any>({})
  const kdc = ref<any>({})
  const identity = ref<any>({})
  const audit = ref<any[]>([])
  const testResult = ref('')

  const form = reactive({
    ldapUrl: '',
    userDnTemplate: 'uid={0},ou=people,dc=example,dc=com',
    baseDn: '',
    bindDn: '',
    bindPassword: '',
    searchFilter: '(uid={0})',
    testUsername: '',
    testPassword: ''
  })

  const caPresent = computed(() => !!tls.value.present)
  const distributedOk = computed(() => (tls.value.hosts || []).some((h: any) => h?.ok))
  const managerHttpsOn = computed(() => !!tls.value.managerHttpsEnabled || !!tls.value.httpDisabled)
  const autoTlsStatus = computed(() => {
    if (managerHttpsOn.value) return 'ON (HTTPS ONLY)'
    if (!caPresent.value) return 'OFF'
    if (distributedOk.value) return 'ENABLED ON AGENTS'
    return 'CA ISSUED'
  })
  const ldapBound = computed(() => !!identity.value.directoryEnabled && !!String(identity.value.ldapUrl || '').trim())
  const wizardTargets = computed(() => (tls.value.wizard && tls.value.wizard.targets) || {})
  const disabling = ref(false)

  const onDisableAutotls = async () => {
    disabling.value = true
    try {
      const r = await api.disableTls()
      message.success(
        r?.httpUrl
          ? `AutoTLS disabled — Manager will restore ${r.httpUrl} after restart (~15s)`
          : 'AutoTLS disabled — restoring HTTP :8080'
      )
      // Give restart time, then try reload (may fail until HTTP is back)
      setTimeout(() => {
        window.location.href = 'http://10.1.0.191:8080/ui/'
      }, 18000)
    } catch {
      message.error('Disable AutoTLS failed')
    } finally {
      disabling.value = false
      await load().catch(() => undefined)
    }
  }

  const applyIdentity = (i: any) => {
    identity.value = i || {}
    form.ldapUrl = i?.ldapUrl || ''
    form.userDnTemplate = i?.userDnTemplate || 'uid={0},ou=people,dc=example,dc=com'
    form.baseDn = i?.baseDn || ''
    form.bindDn = i?.bindDn || ''
    form.searchFilter = i?.searchFilter || '(uid={0})'
    if (!i?.bindPasswordSet) {
      form.bindPassword = ''
    }
  }

  const load = async () => {
    loading.value = true
    try {
      const [t, i, a, k] = await Promise.all([
        api.getTls(),
        api.getIdentity(),
        api.listAudit(),
        api.getKdc().catch(() => ({}))
      ])
      tls.value = t || {}
      applyIdentity(i)
      audit.value = Array.isArray(a) ? a : []
      kdc.value = k || {}
    } catch {
      message.error('Failed to load security settings')
    } finally {
      loading.value = false
    }
  }

  const onWizardDone = async () => {
    showWizard.value = false
    await load()
  }

  const onSaveLdap = async () => {
    if (!form.ldapUrl.trim()) {
      message.error('Enter the LDAP URL')
      return
    }
    saving.value = true
    try {
      const r = await api.saveLdap({
        ldapUrl: form.ldapUrl.trim(),
        userDnTemplate: form.userDnTemplate.trim(),
        baseDn: form.baseDn.trim(),
        bindDn: form.bindDn.trim(),
        bindPassword: form.bindPassword,
        searchFilter: form.searchFilter.trim() || '(uid={0})'
      })
      applyIdentity(r?.identity || r)
      message.success('Saved. Log out and use the Directory tab with an LDAP user.')
    } catch {
      message.error('Save LDAP failed')
    } finally {
      saving.value = false
    }
  }

  const onTestLdap = async () => {
    if (!form.ldapUrl.trim() || !form.testUsername.trim() || !form.testPassword) {
      message.error('Fill LDAP URL + test username + test password')
      return
    }
    testing.value = true
    testResult.value = ''
    try {
      const r = await api.testLdap({
        ldapUrl: form.ldapUrl.trim(),
        userDnTemplate: form.userDnTemplate.trim(),
        baseDn: form.baseDn.trim(),
        bindDn: form.bindDn.trim(),
        bindPassword: form.bindPassword,
        searchFilter: form.searchFilter.trim() || '(uid={0})',
        username: form.testUsername.trim(),
        password: form.testPassword
      })
      testResult.value = r?.ok
        ? `OK — bound as ${r.dn || form.testUsername}`
        : `Failed — ${r?.message || 'bind rejected'}`
      if (r?.ok) {
        message.success('LDAP bind works. Click Save LDAP & enable login.')
      } else {
        message.error(testResult.value)
      }
    } catch {
      testResult.value = 'Failed — request error'
      message.error('Test LDAP failed')
    } finally {
      testing.value = false
    }
  }

  const onEnableKdc = async () => {
    kdc.value = await api.enableKdc()
    message.success(kdc.value.kdcActive ? 'Lab KDC is up' : 'KDC enable finished — check status')
    await load()
  }

  onMounted(load)
</script>

<template>
  <div class="security-page">
    <div class="security-header">
      <div>
        <a-typography-title :level="3" style="margin: 0">Security</a-typography-title>
        <a-typography-text type="secondary">
          AutoTLS, LDAP, and Kerberos — configure here only. Nothing runs until you finish the wizard.
        </a-typography-text>
      </div>
    </div>

    <a-spin :spinning="loading">
      <a-tabs v-model:activeKey="tab">
        <a-tab-pane key="autotls" tab="AutoTLS">
          <div v-if="showWizard" class="wizard-wrap">
            <AutotlsWizard @done="onWizardDone" @cancel="showWizard = false" />
          </div>
          <div v-else>
            <a-row :gutter="16">
              <a-col :span="8">
                <a-card size="small">
                  <a-statistic title="AutoTLS" :value="autoTlsStatus" />
                </a-card>
              </a-col>
              <a-col :span="8">
                <a-card size="small">
                  <a-statistic title="CA" :value="caPresent ? 'Present' : 'Missing'" />
                  <div class="muted">{{ tls.notAfter || '—' }}</div>
                </a-card>
              </a-col>
              <a-col :span="8">
                <a-card size="small">
                  <a-statistic title="Hosts with PEMs" :value="(tls.hosts || []).filter((h) => h.ok).length" />
                  <div class="muted">{{ tls.agentTlsDir || '/etc/beat/tls' }}</div>
                </a-card>
              </a-col>
            </a-row>

            <a-card size="small" style="margin-top: 16px" title="Last wizard choices">
              <a-descriptions bordered size="small" :column="2">
                <a-descriptions-item label="Agents">{{
                  wizardTargets.agents === false ? 'no' : 'yes'
                }}</a-descriptions-item>
                <a-descriptions-item label="Manager server">{{
                  wizardTargets.managerServer ? 'intent' : 'no'
                }}</a-descriptions-item>
                <a-descriptions-item label="Manager UI">{{
                  wizardTargets.managerUi ? 'intent' : 'no'
                }}</a-descriptions-item>
                <a-descriptions-item label="Agent gRPC">{{
                  wizardTargets.agentGrpc ? 'intent' : 'no'
                }}</a-descriptions-item>
                <a-descriptions-item label="Services">{{
                  wizardTargets.services ? 'intent (Apply Config)' : 'no'
                }}</a-descriptions-item>
                <a-descriptions-item label="Updated">{{ tls.wizard?.updatedAt || '—' }}</a-descriptions-item>
              </a-descriptions>
            </a-card>

            <a-alert
              v-if="managerHttpsOn"
              type="warning"
              show-icon
              style="margin-top: 16px"
              message="Manager AutoTLS is ON — plain HTTP http://10.1.0.191:8080 is disabled. Use https://10.1.0.191:8083/ui/"
            />

            <a-space style="margin-top: 16px">
              <a-button type="primary" @click="showWizard = true">
                {{ caPresent ? 'Rotate / Configure AutoTLS' : 'Enable AutoTLS' }}
              </a-button>
              <a-popconfirm
                v-if="managerHttpsOn"
                title="Disable Manager AutoTLS and restore HTTP :8080?"
                ok-text="Disable"
                cancel-text="Cancel"
                @confirm="onDisableAutotls"
              >
                <a-button danger :loading="disabling">Disable AutoTLS</a-button>
              </a-popconfirm>
            </a-space>

            <a-table
              :data-source="tls.hosts || []"
              :pagination="false"
              row-key="hostname"
              size="small"
              style="margin-top: 16px"
            >
              <a-table-column title="Host" data-index="hostname" />
              <a-table-column title="OK" data-index="ok" :width="80">
                <template #default="{ text }">{{ text ? 'yes' : 'no' }}</template>
              </a-table-column>
              <a-table-column title="Path" data-index="path" />
              <a-table-column title="Message" data-index="message" />
              <a-table-column title="At" data-index="at" />
            </a-table>
          </div>
        </a-tab-pane>

        <a-tab-pane key="ldap" tab="LDAP">
          <a-typography-text type="secondary">
            Point BEAT at your directory for UI login. Log out → Directory tab. Local admin always works.
          </a-typography-text>
          <a-card title="LDAP / directory login" size="small" style="margin: 16px 0">
            <a-form layout="vertical">
              <a-form-item label="LDAP URL" required>
                <a-input v-model:value="form.ldapUrl" placeholder="ldap://ldap.example.com:389" />
              </a-form-item>
              <a-form-item label="User DN template ({0} = username)">
                <a-input v-model:value="form.userDnTemplate" placeholder="uid={0},ou=people,dc=example,dc=com" />
              </a-form-item>
              <a-row :gutter="12">
                <a-col :span="12">
                  <a-form-item label="Base DN">
                    <a-input v-model:value="form.baseDn" placeholder="ou=people,dc=example,dc=com" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="Search filter">
                    <a-input v-model:value="form.searchFilter" placeholder="(uid={0})" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-row :gutter="12">
                <a-col :span="12">
                  <a-form-item label="Service bind DN">
                    <a-input v-model:value="form.bindDn" placeholder="cn=readonly,dc=example,dc=com" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="Service bind password">
                    <a-input-password v-model:value="form.bindPassword" placeholder="optional" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-divider />
              <a-row :gutter="12">
                <a-col :span="12">
                  <a-form-item label="Test username">
                    <a-input v-model:value="form.testUsername" placeholder="jsmith" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="Test password">
                    <a-input-password v-model:value="form.testPassword" />
                  </a-form-item>
                </a-col>
              </a-row>
              <a-space>
                <a-button :loading="testing" @click="onTestLdap">Test LDAP bind</a-button>
                <a-button type="primary" :loading="saving" @click="onSaveLdap">Save LDAP & enable login</a-button>
              </a-space>
              <a-alert
                v-if="testResult"
                :type="testResult.startsWith('OK') ? 'success' : 'error'"
                show-icon
                style="margin-top: 12px"
                :message="testResult"
              />
              <a-alert
                v-if="ldapBound"
                type="success"
                show-icon
                style="margin-top: 12px"
                :message="`Directory login ON → ${identity.ldapUrl}`"
              />
            </a-form>
          </a-card>
        </a-tab-pane>

        <a-tab-pane key="kdc" tab="Kerberos">
          <a-card size="small">
            <a-statistic title="Lab KDC" :value="kdc.kdcActive ? 'UP' : 'OFF'" />
            <a-descriptions bordered size="small" :column="1" style="margin-top: 12px">
              <a-descriptions-item label="Realm">{{ kdc.realm || '—' }}</a-descriptions-item>
              <a-descriptions-item label="KDC host">{{ kdc.kdcHost || '—' }}</a-descriptions-item>
            </a-descriptions>
            <a-button style="margin-top: 12px" type="primary" @click="onEnableKdc">Enable lab KDC</a-button>
          </a-card>
        </a-tab-pane>

        <a-tab-pane key="audit" tab="Audit">
          <a-table :data-source="audit" :pagination="{ pageSize: 10 }" row-key="id" size="small">
            <a-table-column title="ID" data-index="id" width="80" />
            <a-table-column title="User" data-index="userId" width="80" />
            <a-table-column title="URI" data-index="uri" />
            <a-table-column title="Tag" data-index="tag" />
            <a-table-column title="Summary" data-index="summary" />
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </a-spin>
  </div>
</template>

<style scoped lang="scss">
  .security-page {
    padding: 8px 4px 24px;
    background: #f5f6f8;
    min-height: 100%;
  }
  .security-header {
    margin-bottom: 12px;
    padding: 8px 4px;
  }
  .wizard-wrap {
    background: #fff;
    border: 1px solid #e8e8e8;
    border-radius: 4px;
    padding: 8px 16px 16px;
  }
  .muted {
    margin-top: 8px;
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }
  :deep(.ant-tabs-content) {
    background: #fff;
    padding: 16px;
    border: 1px solid #f0f0f0;
    border-top: none;
  }
</style>
