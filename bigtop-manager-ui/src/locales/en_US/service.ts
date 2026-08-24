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
  name: 'Service Name',
  required_restart: 'Restart',
  select_service: 'Services',
  assign_component: 'Assign Component',
  configure_service: 'Configure Service',
  service_overview: 'Service Overview',
  install_component: 'Install',
  service_list: 'Service List',
  pending_installation_services: 'Selected Services',
  select_host: 'Select  Host',
  host_preview: 'Host Preview',
  please_enter_search_keyword: 'Please enter search keyword',
  component_host_assignment: 'Assign at least one host for each component',
  service_selection: 'Please select services to install',
  dependencies_conflict_msg: '{0} requires infra service {1} to be installed first',
  dependencies_add_msg: '{0} requires service {1}, add it also?',
  dependencies_remove_msg: '{0} requires service {1}, remove it also?',
  capture_snapshot: 'Capture Snapshot',
  snapshot_management: 'Snapshot Management',
  history_rollback: 'History & Rollback',
  config_history: 'Configuration History',
  config_history_intro:
    'Every Save records a revision. Apply Config creates a new agent process folder. Revert restores DB values; then Apply Config writes a new process folder with the old config.',
  config_history_search: 'Search within the message, the context or the username.',
  show_reverted: 'Show Reverted Configurations',
  history_message: 'Message',
  details: 'Details',
  username: 'Username',
  revision_details: 'Revision Details',
  property: 'Property',
  value: 'Value',
  description: 'Description',
  revert_config_changes: 'Revert Configuration Changes',
  revert_and_apply: 'Revert and Apply Config',
  revert_success: 'Configuration reverted in BEAT DB. Click Apply Config (or use Revert and Apply) so agents write a new process folder.',
  revert_fail: 'Failed to revert configuration',
  stale_config_title: 'Stale configs — Apply / Restart required',
  stale_config_desc:
    'Configs are saved in BEAT DB but not live on hosts yet. Click Apply Config to let agents rewrite files, then Restart. AI will not restart for you.',
  save_restart_hint: 'Saved to BEAT DB. Click Apply Config so agents write files on hosts, then Restart when ready.',
  apply_config: 'Apply Config',
  apply_config_hint: 'Agents regenerate files under /opt/services/… then stop/start. Watch Jobs.',
  apply_config_fail: 'Apply Config failed to start',
  snapshot_name: 'Name',
  snapshot_description: 'Description',
  snapshot_notes: 'Snapshot Notes',
  exact: '{0} requires exactly {1} host(s).',
  range: '{0} requires between {1} and {2} host(s).',
  minOnly: '{0} requires at least {1} host(s).',
  required: 'Field cannot be empty.'
}
