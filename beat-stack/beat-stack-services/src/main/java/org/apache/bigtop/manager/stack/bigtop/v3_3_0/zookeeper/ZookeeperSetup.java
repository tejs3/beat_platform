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
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.zookeeper;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.common.utils.NetUtils;
import org.apache.bigtop.manager.stack.core.enums.ConfigType;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.LocalSettings;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ZookeeperSetup {

    public static ShellResult configure(Params params) {
        log.info("Configuring ZooKeeper");
        ZookeeperParams zookeeperParams = (ZookeeperParams) params;

        String confDir = zookeeperParams.confDir();
        String zookeeperUser = zookeeperParams.user();
        String zookeeperGroup = zookeeperParams.group();
        Map<String, Object> zookeeperEnv = zookeeperParams.zookeeperEnv();
        Map<String, Object> zooCfg = zookeeperParams.zooCfg();
        List<String> zkHostList = LocalSettings.componentHosts("zookeeper_server");

        LinuxFileUtils.createDirectories(
                zookeeperParams.getZookeeperDataDir(), zookeeperUser, zookeeperGroup, Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(
                zookeeperParams.getZookeeperLogDir(), zookeeperUser, zookeeperGroup, Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(
                zookeeperParams.getZookeeperPidDir(), zookeeperUser, zookeeperGroup, Constants.PERMISSION_755, true);

        // server.${host?index+1}=${host}:2888:3888
        zkHostList.sort(String::compareToIgnoreCase);
        StringBuilder zkServerStr = new StringBuilder();
        for (String zkHost : zkHostList) {
            zkServerStr
                    .append(MessageFormat.format("server.{0}={1}:2888:3888", zkHostList.indexOf(zkHost) + 1, zkHost))
                    .append("\n");
        }

        // Match manager-registered host list entry against local hostname / all local IPs.
        // Hosts may be stored as IPs while NetUtils.getHostname() returns an FQDN.
        int myIdIndex = -1;
        java.util.LinkedHashSet<String> identities = new java.util.LinkedHashSet<>();
        identities.add(zookeeperParams.hostname());
        identities.add(NetUtils.getHostname());
        identities.add(NetUtils.getHost());
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs =
                        nics.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress()) {
                        identities.add(addr.getHostAddress());
                        String name = addr.getHostName();
                        if (name != null && !name.isBlank()) {
                            identities.add(name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to identities already collected
        }
        for (String identity : identities) {
            int idx = zkHostList.indexOf(identity);
            if (idx < 0) {
                for (int i = 0; i < zkHostList.size(); i++) {
                    if (zkHostList.get(i).equalsIgnoreCase(identity)) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                myIdIndex = idx;
                break;
            }
        }
        if (myIdIndex < 0) {
            throw new IllegalStateException(MessageFormat.format(
                    "Unable to resolve ZooKeeper myid: identities={0}, hosts={1}",
                    identities,
                    zkHostList));
        }

        LinuxFileUtils.toFile(
                ConfigType.CONTENT,
                MessageFormat.format("{0}/myid", zookeeperParams.getZookeeperDataDir()),
                zookeeperUser,
                zookeeperGroup,
                Constants.PERMISSION_644,
                (myIdIndex + 1) + "");

        HashMap<String, Object> map = new HashMap<>(zooCfg);
        map.remove("content");
        Map<String, Object> paramMap = Map.of("zk_server_str", zkServerStr.toString(), "security_enabled", false);
        LinuxFileUtils.toFileByTemplate(
                zooCfg.get("content").toString(),
                MessageFormat.format("{0}/zoo.cfg", confDir),
                zookeeperUser,
                zookeeperGroup,
                Constants.PERMISSION_644,
                Map.of("model", map),
                paramMap);

        LinuxFileUtils.toFileByTemplate(
                zookeeperEnv.get("content").toString(),
                MessageFormat.format("{0}/zookeeper-env.sh", confDir),
                zookeeperUser,
                zookeeperGroup,
                Constants.PERMISSION_644,
                zookeeperParams.getGlobalParamsMap());

        log.info("Successfully configured ZooKeeper");
        return ShellResult.success();
    }
}
