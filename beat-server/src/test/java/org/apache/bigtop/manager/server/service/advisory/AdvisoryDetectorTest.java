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

import org.apache.bigtop.manager.dao.po.HostPO;
import org.apache.bigtop.manager.dao.po.ServicePO;
import org.apache.bigtop.manager.server.model.vo.AdvisorySuggestionVO;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisoryDetectorTest {

    @Test
    void usedPercentRounds() {
        assertEquals(80, AdvisoryDetector.usedPercent(20L, 100L));
        assertEquals(null, AdvisoryDetector.usedPercent(10L, 0L));
    }

    @Test
    void diskCardFiresAtThreshold() {
        HostPO host = new HostPO();
        host.setId(1L);
        host.setHostname("ravitejacdp3");
        host.setFreeDisk(10L);
        host.setTotalDisk(100L);
        List<AdvisorySuggestionVO> cards = AdvisoryDetector.hostResourceCards(List.of(host));
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).getProblem().contains("disk"));
        assertTrue(cards.get(0).getAdvisoryOnly());
    }

    @Test
    void noDiskCardWhenHealthy() {
        HostPO host = new HostPO();
        host.setId(2L);
        host.setHostname("ravitejacdp1");
        host.setFreeDisk(50L);
        host.setTotalDisk(100L);
        host.setFreeMemorySize(16L);
        host.setTotalMemorySize(32L);
        assertTrue(AdvisoryDetector.hostResourceCards(List.of(host)).isEmpty());
    }

    @Test
    void staleRestartCardUsesSnapshotDiffs() {
        ServicePO svc = new ServicePO();
        svc.setId(9L);
        svc.setName("hadoop");
        svc.setDisplayName("Hadoop");
        svc.setRestartFlag(true);
        String desc =
                "{\"message\":\"x\",\"changes\":[{\"file\":\"hdfs-site\",\"property\":\"dfs.replication\",\"oldValue\":\"3\",\"newValue\":\"1\"}]}";
        AdvisorySuggestionVO card = AdvisoryDetector.staleRestartCard(svc, List.of(), desc, 1);
        assertEquals("stale-config", card.getMode());
        assertTrue(card.getSuggestedFix().contains("dfs.replication"));
        assertTrue(card.getSuggestedFix().contains("3 → 1"));
        assertFalse(card.getSuggestedFix().contains("use AI config review if unsure"));
    }

    @Test
    void staleRestartCardUsesHeuristicWhenNoHistory() {
        ServicePO svc = new ServicePO();
        svc.setId(2L);
        svc.setName("zookeeper");
        svc.setDisplayName("ZooKeeper");
        svc.setRestartFlag(true);
        org.apache.bigtop.manager.dao.po.ServiceConfigPO cfg =
                new org.apache.bigtop.manager.dao.po.ServiceConfigPO();
        cfg.setName("zoo.cfg");
        cfg.setPropertiesJson(
                "[{\"name\":\"maxClientCnxns\",\"value\":\"0\"},{\"name\":\"4lw.commands.whitelist\",\"value\":\"ruok,srvr,mntr,conf\"},{\"name\":\"autopurge.purgeInterval\",\"value\":\"24\"},{\"name\":\"tickTime\",\"value\":\"3000\"},{\"name\":\"admin.serverPort\",\"value\":\"9393\"}]");
        AdvisorySuggestionVO card = AdvisoryDetector.staleRestartCard(svc, List.of(cfg), null, 0);
        assertTrue(card.getSuggestedFix().contains("maxClientCnxns"));
        assertTrue(card.getSuggestedFix().contains("RECOMMENDED CONFIG CHANGES"));
    }

    @Test
    void staleRestartCard() {
        ServicePO svc = new ServicePO();
        svc.setId(9L);
        svc.setName("zookeeper");
        svc.setDisplayName("ZooKeeper");
        svc.setRestartFlag(true);
        List<AdvisorySuggestionVO> cards = AdvisoryDetector.staleRestartCards(List.of(svc));
        assertEquals(1, cards.size());
        assertEquals("stale-config", cards.get(0).getMode());
    }

    @Test
    void zkUnlimitedClientsAndTight4lw() {
        Map<String, String> zoo = new HashMap<>();
        zoo.put("maxClientCnxns", "0");
        zoo.put("4lw.commands.whitelist", "ruok");
        zoo.put("autopurge.purgeInterval", "0");
        zoo.put("tickTime", "8000");
        zoo.put("admin.serverPort", "8080");
        List<AdvisorySuggestionVO> cards = AdvisoryDetector.zookeeperConfigCards("ZooKeeper", 2L, zoo);
        assertEquals(5, cards.size());
        assertTrue(cards.stream().allMatch(AdvisorySuggestionVO::getAdvisoryOnly));
    }

    @Test
    void zkSaneConfigSilent() {
        Map<String, String> zoo = new HashMap<>();
        zoo.put("maxClientCnxns", "60");
        zoo.put("4lw.commands.whitelist", "ruok,srvr,mntr,conf");
        zoo.put("autopurge.purgeInterval", "24");
        zoo.put("tickTime", "3000");
        zoo.put("admin.serverPort", "9393");
        assertTrue(AdvisoryDetector.zookeeperConfigCards("ZooKeeper", 2L, zoo).isEmpty());
    }

    @Test
    void hdfsReplicationVsDatanodes() {
        Map<String, String> hdfs = Map.of("dfs.replication", "3");
        List<AdvisorySuggestionVO> cards = AdvisoryDetector.hadoopConfigCards("Hadoop", 3L, hdfs, 1);
        assertFalse(cards.isEmpty());
        assertTrue(cards.get(0).getProblem().contains("dfs.replication"));
        assertTrue(AdvisoryDetector.hadoopConfigCards("Hadoop", 3L, hdfs, 3).isEmpty());
    }
}
