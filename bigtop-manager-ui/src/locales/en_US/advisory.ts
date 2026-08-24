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

export default {
  title: 'AI Suggestions',
  subtitle: 'Log-backed RCA for unhealthy roles, plus stale configs and capacity cards. Blank when nothing is wrong.',
  refresh: 'Refresh',
  empty: 'No suggestions right now — cluster looks healthy.',
  review_title: 'AI config review',
  review_empty: 'No config issues on this service.',
  review_button: 'AI config review',
  load_failed: 'Failed to load suggestions',
  advise_only: 'Advise only',
  advise_only_banner:
    'AI pulls host logs and asks your configured LLM for exact findings. You still apply Start/Restart/Apply Config in BEAT — AI does not change the cluster by itself.',
  service: 'Service',
  why: 'Why it matters',
  fix: 'Suggested fix',
  verify: 'How to verify',
  confidence: 'Confidence',
  human_applies: 'You decide and apply the change',
  open_page: 'Open AI Suggestions',
  fetch_logs: 'Fetch evidence logs',
  fetch_logs_ok: 'Agent pulled logs (advise only — not applied)',
  fetch_logs_fail: 'Failed to fetch logs via agent',
  evidence: 'Evidence'
}
