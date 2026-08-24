import { formatFromByte } from '@/utils/storage'

type MemHost = {
  totalMemorySize?: number
  freeMemorySize?: number
  totalSwapSpaceSize?: number
  freeSwapSpaceSize?: number
}

export const isResourceEnabled = (total?: number) => Number(total || 0) > 0

export const usedBytes = (total?: number, free?: number) => {
  const t = Number(total || 0)
  const f = Number(free || 0)
  if (t <= 0) return 0
  return Math.max(0, t - f)
}

export const formatUsed = (total?: number, free?: number) => {
  if (!isResourceEnabled(total)) return '—'
  return formatFromByte(usedBytes(total, free))
}

export const memoryEnabled = (h: MemHost) => isResourceEnabled(h.totalMemorySize)
export const swapEnabled = (h: MemHost) => isResourceEnabled(h.totalSwapSpaceSize)
export const formatMemoryUsed = (h: MemHost) => formatUsed(h.totalMemorySize, h.freeMemorySize)
export const formatSwapUsed = (h: MemHost) => formatUsed(h.totalSwapSpaceSize, h.freeSwapSpaceSize)
