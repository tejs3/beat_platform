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

import org.apache.bigtop.manager.grpc.payload.ComponentCommandPayload;
import org.apache.bigtop.manager.stack.bigtop.param.BigtopParams;
import org.apache.bigtop.manager.stack.core.annotations.GlobalParams;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.LocalSettings;

import com.google.auto.service.AutoService;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Getter
@Slf4j
@AutoService(Params.class)
@NoArgsConstructor
public class IcebergParams extends BigtopParams {

    public IcebergParams(ComponentCommandPayload componentCommandPayload) {
        super(componentCommandPayload);
        globalParamsMap.put("iceberg_user", user());
        globalParamsMap.put("iceberg_group", group());
        globalParamsMap.put("iceberg_home", serviceHome());
        globalParamsMap.put("iceberg_conf_dir", confDir());
        globalParamsMap.put("hive_home", hiveHome());
        globalParamsMap.put("hadoop_home", hadoopHome());
    }

    @GlobalParams
    public Map<String, Object> icebergSite() {
        return LocalSettings.configurations(getServiceName(), "iceberg-site");
    }

    public String hiveHome() {
        return stackHome() + "/hive";
    }

    public String hadoopHome() {
        return stackHome() + "/hadoop";
    }

    @Override
    public String getServiceName() {
        return "iceberg";
    }
}
