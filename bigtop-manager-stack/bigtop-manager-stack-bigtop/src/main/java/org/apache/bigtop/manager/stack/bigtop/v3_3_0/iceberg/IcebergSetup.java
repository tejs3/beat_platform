/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.iceberg;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.core.enums.ConfigType;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxOSUtils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.text.MessageFormat;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class IcebergSetup {

    public static ShellResult configure(Params params) {
        log.info("Configuring Iceberg");
        IcebergParams icebergParams = (IcebergParams) params;
        String user = icebergParams.user();
        String group = icebergParams.group();
        String confDir = icebergParams.confDir();
        String serviceHome = icebergParams.serviceHome();
        String libDir = serviceHome + "/lib";

        LinuxFileUtils.createDirectories(serviceHome, user, group, Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(confDir, user, group, Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(libDir, user, group, Constants.PERMISSION_755, true);

        LinuxFileUtils.toFile(
                ConfigType.XML,
                MessageFormat.format("{0}/iceberg-site.xml", confDir),
                user,
                group,
                Constants.PERMISSION_644,
                icebergParams.icebergSite());

        String hiveAux = icebergParams.hiveHome() + "/auxlib";
        LinuxFileUtils.createDirectories(hiveAux, user, group, Constants.PERMISSION_755, true);
        File[] jars = new File(libDir).listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                LinuxFileUtils.copyFile(jar.getAbsolutePath(), hiveAux + "/" + jar.getName());
            }
            LinuxFileUtils.updateOwner(hiveAux, user, group, true);
        }

        try {
            Object warehouse = icebergParams.icebergSite().get("iceberg.warehouse");
            String path = warehouse != null ? warehouse.toString() : "/warehouse/tablespace/iceberg";
            String hadoopHome = icebergParams.hadoopHome();
            String hadoopConf = hadoopHome + "/etc/hadoop";
            LinuxOSUtils.sudoExecCmd(
                    "env HADOOP_CONF_DIR=" + hadoopConf + " " + hadoopHome + "/bin/hdfs dfs -mkdir -p " + path,
                    "hadoop");
            LinuxOSUtils.sudoExecCmd(
                    "env HADOOP_CONF_DIR=" + hadoopConf + " " + hadoopHome
                            + "/bin/hdfs dfs -chown -R hive:hadoop /warehouse/tablespace/iceberg",
                    "hadoop");
        } catch (Exception e) {
            log.warn("Could not prepare Iceberg warehouse: {}", e.getMessage());
        }

        log.info("Successfully configured Iceberg");
        return ShellResult.success();
    }
}
