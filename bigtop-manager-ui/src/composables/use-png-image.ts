/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

const DEFAULT_REPO = 'https://github.com/tejs3/beat-repo3.0.0-1'

/** Map parcel/stack component ids to logo filenames in beat-repo. */
const LOGO_ALIASES: Record<string, string> = {
  'hadoop-hdfs': 'hdfs',
  'hadoop-yarn': 'yarn',
  'hadoop-mapreduce': 'hadoop',
  mapreduce: 'hadoop',
  'ranger-admin': 'ranger',
  'ranger-usersync': 'ranger',
  hbse: 'hbase'
}

const images = import.meta.glob('../assets/images/*.png', { eager: true, import: 'default' })

export function resolveLogoName(serviceOrComponent: string): string {
  const key = (serviceOrComponent || '').toLowerCase().trim()
  if (!key) return 'logo'
  return LOGO_ALIASES[key] || key
}

export function parcelLogoUrl(repoUrl: string, service: string): string {
  const base = (repoUrl || DEFAULT_REPO).trim()
  const slug = base
    .replace(/\/+$/, '')
    .replace(/\.git$/, '')
    .replace(/.*github\.com\//, '')
    .replace(/\/tree\/.*$/, '')
    .replace(/\/releases\/.*$/, '')
  if (!slug) return ''
  return `https://raw.githubusercontent.com/${slug}/main/logos/${resolveLogoName(service)}.png`
}

export function usePngImage(imageName: string = 'logo'): string {
  const resolved = resolveLogoName(imageName)
  const path = `../assets/images/${resolved}.png`
  return (images[path] as string) || (images['../assets/images/logo.png'] as string) || parcelLogoUrl(DEFAULT_REPO, resolved)
}
