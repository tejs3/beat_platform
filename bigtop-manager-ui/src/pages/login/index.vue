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
  import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
  import { getSalt, getNonce, login } from '@/api/login'
  import { getLoginOptions, loginDirectory } from '@/api/beat'
  import { message } from 'ant-design-vue'
  import LoginLang from '@/features/login-lang/index.vue'
  import { deriveKey } from '@/utils/pbkdf2.ts'
  import { useUserStore } from '@/store/user'
  import { useMenuStore } from '@/store/menu'

  const { t } = useI18n()
  const userStore = useUserStore()
  const menuStore = useMenuStore()

  const router = useRouter()

  const formRef = shallowRef()
  const submitLoading = shallowRef(false)
  const loginModel = reactive({
    username: '',
    password: '',
    type: 'account',
    remember: true
  })
  const dirHint = ref('')

  onMounted(async () => {
    try {
      const opts = await getLoginOptions()
      dirHint.value = opts?.directoryHint || ''
    } catch {
      dirHint.value = ''
    }
  })

  const finishLogin = (token: string) => {
    if (loginModel.remember) {
      localStorage.setItem('Token', token)
    } else {
      sessionStorage.setItem('Token', token)
    }
    userStore.getUserInfo()
    menuStore.setupMenu()
    message.success(t('login.login_success'))
    router.push('/')
  }

  const submit = async () => {
    submitLoading.value = true
    const hide = message.loading(t('login.logging_in'), 0)
    try {
      await formRef.value?.validate()
      const username = loginModel.username

      if (loginModel.type === 'directory') {
        const res = await loginDirectory({ username, password: loginModel.password })
        finishLogin(res.token)
        return
      }

      const salt = await getSalt(username).then(async (res: string) => {
        return res
      })

      const nonce = await getNonce(username).then(async (res: string) => {
        return res
      })

      const encryptPwd = deriveKey(loginModel.password, salt)

      const res = await login({
        username: username,
        password: encryptPwd,
        nonce: nonce
      })
      finishLogin(res.token)
    } catch (e) {
      console.warn(e)
    } finally {
      hide()
      submitLoading.value = false
    }
  }
</script>

<template>
  <div class="login-container">
    <div class="login-content">
      <div class="login-main">
        <!-- Login box header -->
        <div class="login-header">
          <div class="login-header-left">
            <img class="login-logo" src="@/assets/logo.svg" alt="logo" />
            <div class="login-title">BEAT Manager</div>
            <div class="login-desc">{{ t('login.desc') }}</div>
          </div>
          <div class="login-header-right"><login-lang /></div>
        </div>
        <a-divider class="m-0" />
        <!-- Login box body -->
        <div class="login-body">
          <!-- On the left side of the login box -->
          <div class="login-body-left">
            <img class="login-body-left-img" src="@/assets/images/login.png" alt="login" />
          </div>
          <a-divider class="login-body-divider m-0" type="vertical" />
          <!-- Right side of the login box -->
          <div class="login-body-right">
            <div class="login-body-right-tips">{{ t('login.tips') }}</div>
            <a-form ref="formRef" class="login-body-right-form" :model="loginModel">
              <a-tabs v-model:active-key="loginModel.type" centered>
                <a-tab-pane key="account" :tab="t('login.tab_account')" />
                <a-tab-pane key="directory" :tab="t('login.tab_directory')" />
              </a-tabs>
              <a-typography-text v-if="loginModel.type === 'directory' && dirHint" type="secondary" style="display:block;margin-bottom:8px">
                {{ dirHint }}
              </a-typography-text>
              <a-form-item
                name="username"
                :rules="[
                  {
                    required: true,
                    message: t('login.username_required')
                  }
                ]"
              >
                <a-input
                  v-model:value="loginModel.username"
                  allow-clear
                  :placeholder="
                    loginModel.type === 'directory'
                      ? t('login.directory_username_placeholder')
                      : t('login.username_placeholder')
                  "
                  size="large"
                  @press-enter="submit"
                >
                  <template #prefix>
                    <user-outlined />
                  </template>
                </a-input>
              </a-form-item>
              <a-form-item
                name="password"
                :rules="[
                  {
                    required: true,
                    message: t('login.password_required')
                  }
                ]"
              >
                <a-input-password
                  v-model:value="loginModel.password"
                  allow-clear
                  :placeholder="t('login.password_placeholder')"
                  size="large"
                  @press-enter="submit"
                >
                  <template #prefix>
                    <lock-outlined />
                  </template>
                </a-input-password>
              </a-form-item>
              <div class="login-body-right-form-bottom">
                <a-checkbox v-model:checked="loginModel.remember">
                  {{ t('login.remember_me') }}
                </a-checkbox>
              </div>
              <a-button type="primary" block :loading="submitLoading" size="large" @click="submit">
                {{ t('login.submit') }}
              </a-button>
            </a-form>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .login-container {
    @include flexbox($direction: column);
    height: 100vh;
    overflow: auto;
    background:
      radial-gradient(ellipse at 20% 10%, rgba(42, 155, 181, 0.18), transparent 50%),
      radial-gradient(ellipse at 80% 90%, rgba(11, 31, 51, 0.12), transparent 45%),
      linear-gradient(165deg, #f4f8fa 0%, #e8eef2 55%, #dfe8ee 100%);

    .login-content {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      @include flexbox($justify: center, $align: center);

      .login-main {
        border-radius: 10px;
        box-shadow: 0 18px 48px rgba(11, 31, 51, 0.14);
        overflow: hidden;
        border: 1px solid rgba(18, 58, 82, 0.08);
        background: #fff;

        @media screen and (max-width: 767px) {
          width: 350px;
        }

        @media (min-width: 768px) and (max-width: 991px) {
          width: 400px;
        }

        .login-header {
          @include flexbox($justify: space-between, $align: center);
          padding: 0.75rem 1.25rem;
          background: linear-gradient(90deg, #0b1f33 0%, #123a52 100%);

          .login-header-left {
            @include flexbox($justify: space-between, $align: center);

            .login-title {
              font-weight: 700;
              font-size: 28px;
              letter-spacing: 0.06em;
              color: #e8f4f8;

              @media (max-width: 991px) {
                font-size: 18px;
              }
            }

            .login-logo {
              width: 40px;
              height: 40px;
              margin-right: 0.85rem;
              border-radius: 8px;
            }

            .login-desc {
              position: relative;
              top: 4px;
              color: rgba(232, 244, 248, 0.72);
              font-size: 13px;
              margin-left: 1rem;

              @media (max-width: 991px) {
                display: none;
              }
            }
          }
        }

        .login-body {
          display: flex;
          box-sizing: border-box;
          min-height: 520px;

          @media (max-width: 991px) {
            min-height: 400px;
          }

          .login-body-left {
            @include flexbox($justify: center, $align: center);
            min-height: 520px;
            width: 700px;
            background: linear-gradient(145deg, #0f2a3d 0%, #1a6b8a 100%);

            @media (max-width: 991px) {
              display: none;
            }

            .login-body-left-img {
              height: 83.333333%;
              width: 83.333333%;
              opacity: 0.92;
              filter: saturate(0.85) contrast(1.05);
            }
          }

          .login-body-divider {
            min-height: 520px;

            @media (max-width: 991px) {
              display: none;
            }
          }

          .login-body-right {
            @include flexbox($direction: column, $justify: center, $align: center);
            width: 335px;
            padding: 0 1.25rem;

            @media (max-width: 991px) {
              width: 100%;
            }

            .login-body-right-tips {
              text-align: center;
              padding: 1.5rem 0;
              font-size: 1.35rem;
              line-height: 2rem;
              color: #0b1f33;
              font-weight: 600;
            }

            .login-body-right-form {
              .login-body-right-form-bottom {
                margin-bottom: 24px;
                @include flexbox($justify: space-between, $align: center);
              }
            }
          }
        }
      }
    }
  }
</style>
