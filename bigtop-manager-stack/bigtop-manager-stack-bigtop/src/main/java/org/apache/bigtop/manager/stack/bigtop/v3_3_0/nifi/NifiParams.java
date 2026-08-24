package org.apache.bigtop.manager.stack.bigtop.v3_3_0.nifi;

import org.apache.bigtop.manager.grpc.payload.ComponentCommandPayload;
import org.apache.bigtop.manager.stack.bigtop.param.BigtopParams;
import org.apache.bigtop.manager.stack.core.annotations.GlobalParams;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.LocalSettings;

import com.google.auto.service.AutoService;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Getter
@Slf4j
@AutoService(Params.class)
@NoArgsConstructor
public class NifiParams extends BigtopParams {

    private String logDir = "/var/log/nifi";
    private String pidDir = "/var/run/nifi";

    public NifiParams(ComponentCommandPayload componentCommandPayload) {
        super(componentCommandPayload);
        globalParamsMap.put("nifi_user", user());
        globalParamsMap.put("nifi_group", group());
        globalParamsMap.put("java_home", javaHome());
        globalParamsMap.put("nifi_home", serviceHome());
        globalParamsMap.put("nifi_conf_dir", confDir());
    }

    public String pidFile(String component) {
        return pidDir + "/" + component + ".pid";
    }

    @GlobalParams
    public Map<String, Object> site() {
        Map<String, Object> cfg = LocalSettings.configurations(getServiceName(), "nifi-site");
        return cfg != null ? cfg : new HashMap<>();
    }

    @GlobalParams
    public Map<String, Object> env() {
        Map<String, Object> cfg = LocalSettings.configurations(getServiceName(), "nifi-env");
        if (cfg != null) {
            if (cfg.get("log_dir") != null) {
                logDir = cfg.get("log_dir").toString();
            }
            if (cfg.get("pid_dir") != null) {
                pidDir = cfg.get("pid_dir").toString();
            }
        }
        return cfg != null ? cfg : new HashMap<>();
    }

    @Override
    public String legacyConfDir() {
        return serviceHome() + "/conf";
    }

    @Override
    public String getServiceName() {
        return "nifi";
    }
}
