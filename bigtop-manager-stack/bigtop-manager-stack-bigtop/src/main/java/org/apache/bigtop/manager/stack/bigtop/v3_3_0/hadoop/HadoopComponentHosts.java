/*
 * Resolve component hosts for legacy Hadoop names and BEAT split HDFS/YARN names.
 */
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop;

import org.apache.bigtop.manager.stack.core.utils.LocalSettings;

import java.util.ArrayList;
import java.util.List;

public final class HadoopComponentHosts {

    private HadoopComponentHosts() {}

    public static List<String> hosts(String... names) {
        for (String name : names) {
            List<String> hosts = LocalSettings.componentHosts(name);
            if (hosts != null && !hosts.isEmpty()) {
                return hosts;
            }
        }
        return new ArrayList<>();
    }

    public static List<String> namenodes() {
        return hosts("hdfs_namenode", "namenode");
    }

    public static List<String> datanodes() {
        return hosts("hdfs_datanode", "datanode");
    }

    public static List<String> journalnodes() {
        return hosts("hdfs_journalnode", "journalnode");
    }

    public static List<String> resourcemanagers() {
        return hosts("yarn_resourcemanager", "resourcemanager");
    }
}
