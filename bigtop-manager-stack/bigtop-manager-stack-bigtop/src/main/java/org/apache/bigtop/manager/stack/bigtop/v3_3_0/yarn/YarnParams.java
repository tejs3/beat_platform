/*
 * BEAT YARN Params — separate service from HDFS; shares Hadoop binary home.
 */
package org.apache.bigtop.manager.stack.bigtop.v3_3_0.yarn;

import org.apache.bigtop.manager.grpc.payload.ComponentCommandPayload;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopParams;
import org.apache.bigtop.manager.stack.core.spi.param.Params;

import com.google.auto.service.AutoService;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoService(Params.class)
@NoArgsConstructor
public class YarnParams extends HadoopParams {

    public YarnParams(ComponentCommandPayload componentCommandPayload) {
        super(componentCommandPayload);
        globalParamsMap.put("hadoop_home", serviceHome());
        globalParamsMap.put("hadoop_conf_dir", confDir());
        globalParamsMap.put("hadoop_libexec_dir", serviceHome() + "/libexec");
    }

    /** Shared Hadoop parcel install path (not /services/yarn). */
    @Override
    public String serviceHome() {
        return stackHome() + "/hadoop";
    }

    @Override
    public String getServiceName() {
        return "yarn";
    }
}
