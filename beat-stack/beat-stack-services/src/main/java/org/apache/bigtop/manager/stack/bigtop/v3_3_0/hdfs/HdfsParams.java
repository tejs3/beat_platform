/*
 * BEAT HDFS Params — separate service from YARN; shares Hadoop binary home.
 */
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.hdfs;

import org.apache.bigtop.manager.grpc.payload.ComponentCommandPayload;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopComponentHosts;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopParams;
import org.apache.bigtop.manager.stack.core.spi.param.Params;

import com.google.auto.service.AutoService;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoService(Params.class)
@NoArgsConstructor
public class HdfsParams extends HadoopParams {

    public HdfsParams(ComponentCommandPayload componentCommandPayload) {
        super(componentCommandPayload);
        globalParamsMap.put("hadoop_home", serviceHome());
        globalParamsMap.put("hadoop_conf_dir", confDir());
        globalParamsMap.put("hadoop_libexec_dir", serviceHome() + "/libexec");
        globalParamsMap.put("datanode_hosts", HadoopComponentHosts.datanodes());
    }

    /** Shared Hadoop parcel install path (not /services/hdfs). */
    @Override
    public String serviceHome() {
        return stackHome() + "/hadoop";
    }

    @Override
    public String getServiceName() {
        return "hdfs";
    }
}
