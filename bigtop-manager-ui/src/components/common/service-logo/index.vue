<!--
  Service logo from beat-repo (normal parcel logos).
-->
<script setup lang="ts">
  import { parcelLogoUrl, resolveLogoName } from '@/composables/use-png-image'

  const props = withDefaults(
    defineProps<{
      name: string
      size?: number
      repoUrl?: string
    }>(),
    { size: 48, repoUrl: '' }
  )

  const label = computed(() => resolveLogoName(props.name).toUpperCase())
  const primarySrc = computed(() => parcelLogoUrl(props.repoUrl, props.name))
  const fallbackSrc = computed(() =>
    parcelLogoUrl('https://github.com/tejs3/beat-repo3.0.0-1', props.name)
  )
  const imgSrc = ref('')
  const triedFallback = ref(false)

  watch(
    () => [props.name, props.repoUrl],
    () => {
      triedFallback.value = false
      imgSrc.value = primarySrc.value || fallbackSrc.value
    },
    { immediate: true }
  )

  const onError = () => {
    if (!triedFallback.value && fallbackSrc.value && imgSrc.value !== fallbackSrc.value) {
      triedFallback.value = true
      imgSrc.value = fallbackSrc.value
      return
    }
    imgSrc.value = ''
  }
</script>

<template>
  <div class="svc-logo" :style="{ width: `${size}px`, height: `${size}px` }">
    <img v-if="imgSrc" :src="imgSrc" :alt="label" class="svc-logo-img" @error="onError" />
    <span v-else class="svc-logo-fallback">{{ label.slice(0, 3) }}</span>
  </div>
</template>

<style scoped lang="scss">
  .svc-logo {
    flex-shrink: 0;
    border-radius: 8px;
    background: #f5f5f5;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .svc-logo-img {
    width: 100%;
    height: 100%;
    object-fit: contain;
    padding: 4px;
  }
  .svc-logo-fallback {
    font-size: 11px;
    font-weight: 600;
    color: #666;
  }
</style>
