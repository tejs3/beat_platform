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
  import { useMenuStore } from '@/store/menu/index'
  import AiAssistant from '@/features/ai-assistant/index.vue'

  const { t } = useI18n()
  const route = useRoute()
  const menuStore = useMenuStore()

  const spaceSize = ref(16)
  const aiAssistantRef = ref<InstanceType<typeof AiAssistant> | null>(null)
  const { headerSelectedKey, headerMenus } = storeToRefs(menuStore)

  watch(
    () => route,
    (val) => {
      const isHeader = headerMenus.value.some((v) => v.path === val.matched[0].path)
      if (!isHeader) {
        headerSelectedKey.value = ''
      }
    },
    { deep: true, immediate: true }
  )

  const handleCommunication = () => {
    aiAssistantRef.value?.controlVisible()
  }
</script>

<template>
  <a-layout-header class="header">
    <h1 class="header-left common-layout">
      <span class="beat-mark" aria-label="BEAT Manager">BEAT</span>
    </h1>
    <div class="header-menu">
      <a-menu
        :selected-keys="[headerSelectedKey]"
        theme="dark"
        mode="horizontal"
        @select="({ key }) => menuStore.onHeaderClick(key as string)"
      >
        <a-menu-item v-for="menuRoute of headerMenus" :key="menuRoute.path">
          {{ t(menuRoute.meta?.title || '') }}
        </a-menu-item>
      </a-menu>
    </div>
    <div class="header-right common-layout">
      <a-space :size="spaceSize">
        <user-avatar />
        <div class="header-item" @click="handleCommunication">
          <svg-icon name="communication" />
        </div>
        <select-lang />
      </a-space>
    </div>
    <ai-assistant ref="aiAssistantRef" />
  </a-layout-header>
</template>

<style scoped lang="scss">
  .common-layout {
    @include flexbox($justify: center, $align: center);
    height: 100%;
  }
  .header {
    @include flexbox($justify: space-between, $align: center);
    padding-inline: 0 $space-md;
    height: $layout-header-height;
    background: linear-gradient(90deg, #0b4f8a 0%, #1a73c7 45%, #1565b0 100%) !important;
    .header-menu {
      flex: 1;
      :deep(.ant-menu) {
        background: transparent;
      }
    }
    .header-left {
      width: $layout-sider-width;
      margin: 0;
      flex-shrink: 0;
      .beat-mark {
        font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;
        font-weight: 750;
        font-size: 22px;
        letter-spacing: 0.18em;
        color: #e8f4f8;
        padding-left: 4px;
      }
    }

    nav {
      color: $color-white;
    }
  }
</style>
