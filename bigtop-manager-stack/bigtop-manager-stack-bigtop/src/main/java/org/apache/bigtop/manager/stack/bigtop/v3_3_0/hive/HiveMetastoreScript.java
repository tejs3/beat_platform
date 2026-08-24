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
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.hive;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.constants.MessageConstants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.grpc.pojo.RepoInfo;
import org.apache.bigtop.manager.stack.core.exception.StackException;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.spi.script.AbstractServerScript;
import org.apache.bigtop.manager.stack.core.spi.script.Script;
import org.apache.bigtop.manager.stack.core.tarball.FileDownloader;
import org.apache.bigtop.manager.stack.core.utils.LocalSettings;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxOSUtils;

import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;

@Slf4j
@AutoService(Script.class)
public class HiveMetastoreScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");

        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);

        return HiveSetup.configure(params);
    }

    @Override
    public ShellResult init(Params params) {
        downloadJdbcDriver(params);
        ensureWarehouseDirs(params);
        initSchema(params);
        return ShellResult.success();
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        HiveParams hiveParams = (HiveParams) params;
        try {
            initSchema(params);
            String cmd = MessageFormat.format(
                    "{0}/hive-service.sh metastore " + hiveParams.getHiveMetastorePidFile(),
                    hiveParams.serviceHome() + "/bin");
            ShellResult shellResult = LinuxOSUtils.sudoExecCmd(cmd, hiveParams.user());
            if (shellResult.getExitCode() != 0) {
                throw new StackException("Failed to start HiveMetastore: {0}", shellResult.getErrMsg());
            }
            long startTime = System.currentTimeMillis();
            long maxWaitTime = 5000;
            long pollInterval = 500;

            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                ShellResult statusResult = status(params);
                if (statusResult.getExitCode() == 0) {
                    return statusResult;
                }
                Thread.sleep(pollInterval);
            }
            return status(params);
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        HiveParams hiveParams = (HiveParams) params;
        int pid = Integer.parseInt(
                LinuxFileUtils.readFile(hiveParams.getHiveMetastorePidFile()).replaceAll("\r|\n", ""));
        String cmd = "kill -9 " + pid;
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, hiveParams.user());
        } catch (IOException e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        HiveParams hiveParams = (HiveParams) params;
        return LinuxOSUtils.checkProcess(hiveParams.getHiveMetastorePidFile());
    }

    private String dbType(Params params) {
        Object type = LocalSettings.configurations(params.getServiceName(), "hive-site")
                .get("hive.metastore.db.type");
        if (type == null || type.toString().isBlank()) {
            List<String> mysqlHosts = LocalSettings.componentHosts("mysql_server");
            return mysqlHosts != null && !mysqlHosts.isEmpty() ? "mysql" : "postgres";
        }
        return type.toString().trim().toLowerCase();
    }

    private void downloadJdbcDriver(Params params) {
        String type = dbType(params);
        if ("postgres".equals(type) || "postgresql".equals(type)) {
            String libDir = params.serviceHome() + "/lib";
            java.io.File jar = new java.io.File(libDir + "/postgresql-42.7.4.jar");
            if (!jar.exists()) {
                String base = params.repo() != null ? params.repo().getBaseUrl() : "http://127.0.0.1:8080/ui/repo";
                FileDownloader.download(base + "/postgresql-42.7.4.jar", libDir);
            }
            LinuxFileUtils.updateOwner(libDir, params.user(), params.group(), true);
            LinuxFileUtils.updatePermissions(libDir, Constants.PERMISSION_755, true);
            return;
        }
        RepoInfo repoInfo = LocalSettings.repo("mysql-connector-j");
        FileDownloader.download(params.stackHome(), repoInfo);
        LinuxFileUtils.moveFile(params.stackHome() + "/mysql-connector-j-8.0.33.jar", params.serviceHome() + "/lib/");
        LinuxFileUtils.updateOwner(params.serviceHome() + "/lib", params.user(), params.group(), true);
        LinuxFileUtils.updatePermissions(params.serviceHome() + "/lib", Constants.PERMISSION_755, true);
    }

    private void ensureWarehouseDirs(Params params) {
        try {
            String hadoopHome = ((HiveParams) params).hadoopHome();
            String confDir = hadoopHome + "/etc/hadoop";
            String[] dirs = {
                "/warehouse/tablespace/managed/hive",
                "/warehouse/tablespace/external/hive",
                "/warehouse/tablespace/iceberg"
            };
            for (String dir : dirs) {
                String cmd = "env HADOOP_CONF_DIR=" + confDir + " " + hadoopHome + "/bin/hdfs dfs -mkdir -p " + dir;
                LinuxOSUtils.sudoExecCmd(cmd, "hadoop");
            }
            LinuxOSUtils.sudoExecCmd(
                    "env HADOOP_CONF_DIR=" + confDir + " " + hadoopHome
                            + "/bin/hdfs dfs -chown -R hive:hadoop /warehouse",
                    "hadoop");
            LinuxOSUtils.sudoExecCmd(
                    "env HADOOP_CONF_DIR=" + confDir + " " + hadoopHome + "/bin/hdfs dfs -chmod -R 775 /warehouse",
                    "hadoop");
        } catch (Exception e) {
            log.warn("Could not prepare Hive warehouse dirs: {}", e.getMessage());
        }
    }

    private void initSchema(Params params) {
        try {
            HiveParams hiveParams = (HiveParams) params;
            String type = dbType(params);
            if ("postgresql".equals(type)) {
                type = "postgres";
            }
            String cmd = hiveParams.serviceHome() + "/bin/schematool -info -dbType " + type;
            ShellResult shellResult = LinuxOSUtils.sudoExecCmd(cmd, hiveParams.user());
            String err = shellResult.getErrMsg() == null ? "" : shellResult.getErrMsg();
            String out = shellResult.getOutput() == null ? "" : shellResult.getOutput();
            boolean missing = shellResult.getExitCode() != MessageConstants.SUCCESS_CODE
                    && (err.toLowerCase().contains("does not exist")
                            || err.toLowerCase().contains("doesn't exist")
                            || out.toLowerCase().contains("no valid schema")
                            || err.toLowerCase().contains("no valid schema")
                            || err.toLowerCase().contains("failed to get schema version")
                            || out.toLowerCase().contains("failed to get schema version"));
            if (missing) {
                cmd = "nohup " + hiveParams.serviceHome() + "/bin/schematool -initSchema -dbType " + type
                        + " > /tmp/hive-schematool.log 2>&1 &";
                shellResult = LinuxOSUtils.sudoExecCmd(cmd, hiveParams.user());
                Thread.sleep(8000);
                if (shellResult.getExitCode() != MessageConstants.SUCCESS_CODE) {
                    throw new StackException(shellResult.getErrMsg());
                }
            }
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "hive_metastore";
    }
}
