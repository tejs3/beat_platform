package org.apache.bigtop.manager.stack.bigtop.v3_3_0.ranger;

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
public class RangerParams extends BigtopParams {

    private String rangerLogDir = "/var/log/ranger";
    private String rangerPidDir = "/var/run/ranger";

    public RangerParams(ComponentCommandPayload componentCommandPayload) {
        super(componentCommandPayload);
        globalParamsMap.put("ranger_user", user());
        globalParamsMap.put("ranger_group", group());
        globalParamsMap.put("java_home", javaHome());
        globalParamsMap.put("ranger_home", serviceHome());
        globalParamsMap.put("ranger_admin_home", adminHome());
        globalParamsMap.put("ranger_usersync_home", usersyncHome());
        globalParamsMap.put("ranger_conf_dir", confDir());
    }

    public String adminHome() {
        return serviceHome() + "/ranger-admin";
    }

    public String usersyncHome() {
        return serviceHome() + "/ranger-usersync";
    }

    public String adminPidFile() {
        return rangerPidDir + "/rangeradmin.pid";
    }

    public String usersyncPidFile() {
        return rangerPidDir + "/usersync.pid";
    }

    @GlobalParams
    public Map<String, Object> rangerAdmin() {
        Map<String, Object> cfg = LocalSettings.configurations(getServiceName(), "ranger-admin");
        return cfg != null ? cfg : new HashMap<>();
    }

    @GlobalParams
    public Map<String, Object> rangerUsersync() {
        Map<String, Object> cfg = LocalSettings.configurations(getServiceName(), "ranger-usersync");
        return cfg != null ? cfg : new HashMap<>();
    }

    @GlobalParams
    public Map<String, Object> rangerEnv() {
        Map<String, Object> cfg = LocalSettings.configurations(getServiceName(), "ranger-env");
        if (cfg != null) {
            if (cfg.get("ranger_log_dir") != null) {
                rangerLogDir = cfg.get("ranger_log_dir").toString();
            }
            if (cfg.get("ranger_pid_dir") != null) {
                rangerPidDir = cfg.get("ranger_pid_dir").toString();
            }
        }
        return cfg != null ? cfg : new HashMap<>();
    }

    @Override
    public String getServiceName() {
        return "ranger";
    }
}
