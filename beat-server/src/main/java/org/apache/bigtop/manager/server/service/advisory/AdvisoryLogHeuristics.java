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
package org.apache.bigtop.manager.server.service.advisory;

import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic RCA from known log signatures — used when LLM is missing/fails,
 * so operators never only see "open Configs / restart" fluff.
 */
public final class AdvisoryLogHeuristics {

    private static final Pattern KEYTAB_SECURE = Pattern.compile(
            "(?i)Running in secure mode,? but config doesn'?t have a keytab|keytab.*(not found|missing)|LoginException");
    private static final Pattern YARN_RM_8031 = Pattern.compile(
            "(?i)Retrying connect to server:.*:8031|Connection refused.*:8031|Failed to connect.*8031");
    private static final Pattern NN_8020 = Pattern.compile(
            "(?i)Retrying connect to server:.*:8020|Connection refused.*:8020|Failed on local exception.*8020");
    private static final Pattern ZK_2181 = Pattern.compile(
            "(?i)ConnectionLoss|KeeperException|Connection refused.*:2181|Unable to connect to zookeeper");
    private static final Pattern OOM = Pattern.compile("(?i)OutOfMemoryError|Java heap space|GC overhead");
    private static final Pattern BIND_IN_USE = Pattern.compile("(?i)Address already in use|BindException");
    private static final Pattern PERM_DENIED = Pattern.compile("(?i)Permission denied|AccessControlException");

    private AdvisoryLogHeuristics() {}

    /**
     * @return true if a concrete fix was applied to the card
     */
    public static boolean applyIfKnown(AdvisorySuggestionVO base, String serviceKey, String logs) {
        if (base == null || logs == null || logs.isBlank()) {
            return false;
        }
        String svc = serviceKey == null ? "" : serviceKey.toLowerCase(Locale.ROOT);
        String text = logs;

        if (KEYTAB_SECURE.matcher(text).find()
                && (svc.contains("hbase")
                        || text.toLowerCase(Locale.ROOT).contains("hbase")
                        || text.toLowerCase(Locale.ROOT).contains("regionserver")
                        || text.toLowerCase(Locale.ROOT).contains("hmaster"))) {
            base.setWhyItMatters(
                    "From host logs: HBase is starting in Kerberos/secure mode but no keytab is configured.\n"
                            + "Exact error: Running in secure mode, but config doesn't have a keytab");
            base.setSuggestedFix(
                    """
                    CONFIG CHANGES (lab without Kerberos — use simple auth):
                    - [hbase-site] hbase.security.authentication = simple
                    - [hbase-site] hbase.security.authorization = false
                    - [core-site] hadoop.security.authentication = simple

                    If you intentionally want Kerberos instead:
                    - [hbase-site] hbase.master.keytab.file = /etc/security/keytabs/hbase.service.keytab
                    - [hbase-site] hbase.regionserver.keytab.file = /etc/security/keytabs/hbase.service.keytab
                    - [hbase-site] hbase.master.kerberos.principal = hbase/_HOST@YOUR.REALM
                    - [hbase-site] hbase.regionserver.kerberos.principal = hbase/_HOST@YOUR.REALM

                    Then in BEAT:
                    1) Open HBase → Configs, set the simple-auth values above (or keytabs if using Kerberos).
                    2) Save + Apply Config.
                    3) Restart HMaster and RegionServers.
                    """
                            .trim());
            base.setHowToVerify("HMaster/RegionServer status is healthy; logs no longer mention missing keytab.");
            return true;
        }

        if (YARN_RM_8031.matcher(text).find()
                || (svc.contains("hadoop") && text.contains(":8031"))) {
            base.setWhyItMatters(
                    "From host logs: clients/NodeManagers cannot reach ResourceManager RPC on port 8031 "
                            + "(Retrying connect to server …:8031). RM is down, wrong host, or firewall.");
            base.setSuggestedFix(
                    """
                    CONFIG / ROLE CHECK:
                    - [yarn-site] yarn.resourcemanager.hostname = <RM host FQDN>
                    - [yarn-site] yarn.resourcemanager.address = <RM host>:8031
                    - Confirm ResourceManager component is Started on that host in BEAT → Hadoop → Components

                    Then in BEAT:
                    1) Start / Restart ResourceManager first.
                    2) If address is wrong, set yarn.resourcemanager.hostname / address in yarn-site, Save + Apply Config, Restart RM + NodeManagers.
                    3) Do not restart only NodeManagers while RM is down — they will keep retrying :8031.
                    """
                            .trim());
            base.setHowToVerify("ResourceManager healthy; NM logs stop retrying :8031; YARN UI shows NMs live.");
            return true;
        }

        if (NN_8020.matcher(text).find()) {
            base.setWhyItMatters(
                    "From host logs: cannot reach HDFS NameNode RPC on port 8020 (connection retries/refused).");
            base.setSuggestedFix(
                    """
                    CONFIG / ROLE CHECK:
                    - [core-site] fs.defaultFS = hdfs://<namenode-host>:8020
                    - Confirm NameNode is Started in BEAT → Hadoop → Components

                    Then in BEAT:
                    1) Start NameNode (and JournalNodes/ZKFC if HA).
                    2) Fix fs.defaultFS if it points at a dead host, Save + Apply Config, Restart dependent services.
                    """
                            .trim());
            base.setHowToVerify("hdfs dfs -ls / works; DataNode/NameNode status healthy.");
            return true;
        }

        if (ZK_2181.matcher(text).find() && (svc.contains("hbase") || svc.contains("hadoop") || svc.contains("hive"))) {
            base.setWhyItMatters(
                    "From host logs: ZooKeeper quorum is unreachable (ConnectionLoss / :2181). Dependent services cannot start.");
            base.setSuggestedFix(
                    """
                    CONFIG / ROLE CHECK:
                    - [hbase-site] hbase.zookeeper.quorum = <zk-host1>,<zk-host2>,...
                    - [zoo.cfg] clientPort = 2181 (ZooKeeper service)
                    - Start ZooKeeper servers in BEAT before HBase/Hadoop dependents

                    Then in BEAT:
                    1) Start ZooKeeper on all quorum hosts.
                    2) Fix quorum host list if wrong, Save + Apply Config on the dependent service, Restart it.
                    """
                            .trim());
            base.setHowToVerify("echo ruok | nc <zk-host> 2181 returns imok; dependent service becomes healthy.");
            return true;
        }

        if (OOM.matcher(text).find()) {
            base.setWhyItMatters("From host logs: JVM OutOfMemoryError / Java heap space on this role.");
            String heapProp = svc.contains("hbase")
                    ? "- [hbase-env] HBASE_HEAPSIZE = 2048 (or higher if host RAM allows)"
                    : svc.contains("hadoop") || text.toLowerCase(Locale.ROOT).contains("nodemanager")
                            ? "- [yarn-site] yarn.nodemanager.resource.memory-mb = <lower if host small>\n"
                                    + "- [hadoop-env] HADOOP_HEAPSIZE_MAX = 1024 (raise carefully)"
                            : "- Raise the service heap in its *-env / site config (do not exceed free host RAM)";
            base.setSuggestedFix(
                    "CONFIG CHANGES:\n"
                            + heapProp
                            + "\n\nThen in BEAT: Save + Apply Config → Restart the role that OOM'd.\n"
                            + "Also free memory on the host if usage is >90%.");
            base.setHowToVerify("Role stays up; no OutOfMemoryError in new logs.");
            return true;
        }

        if (BIND_IN_USE.matcher(text).find()) {
            base.setWhyItMatters("From host logs: Address already in use (BindException) — port conflict.");
            base.setSuggestedFix(
                    """
                    1) On the host, find the process holding the port (ss -lntp | grep <port>).
                    2) Stop the duplicate process OR change the service port in Configs.
                    3) In BEAT → Components, Start the role again.
                    """
                            .trim());
            base.setHowToVerify("Role starts; BindException gone from logs.");
            return true;
        }

        if (PERM_DENIED.matcher(text).find()) {
            base.setWhyItMatters("From host logs: Permission denied / AccessControlException on dirs or HDFS paths.");
            base.setSuggestedFix(
                    """
                    1) Check process dirs / data dirs ownership for the service user in BEAT process layout.
                    2) Fix filesystem permissions on the host (chown/chmod) for the service account.
                    3) Restart the role from BEAT → Components.
                    """
                            .trim());
            base.setHowToVerify("Role starts without AccessControlException.");
            return true;
        }

        return false;
    }
}
