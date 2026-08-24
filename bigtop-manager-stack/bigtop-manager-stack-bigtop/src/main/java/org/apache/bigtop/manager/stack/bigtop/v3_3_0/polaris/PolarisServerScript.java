package org.apache.bigtop.manager.stack.bigtop.v3_3_0.polaris;

import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.core.exception.StackException;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.spi.script.AbstractServerScript;
import org.apache.bigtop.manager.stack.core.spi.script.Script;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxOSUtils;

import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;
import java.util.Properties;

@Slf4j
@AutoService(Script.class)
public class PolarisServerScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return PolarisSetup.configure(params);
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        PolarisParams p = (PolarisParams) params;
        String cmd = MessageFormat.format("export JAVA_HOME={2}; if [ -x {0}/bin/polaris.sh ]; then {0}/bin/polaris.sh start; elif [ -x {0}/bin/admin ]; then nohup {0}/bin/admin server > {0}/logs/polaris.out 2>&1 & echo $! > {3}/polaris_server.pid; else ls {0}/bin; exit 1; fi", p.serviceHome(), p.confDir(), p.javaHome(), p.getPidDir());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        PolarisParams p = (PolarisParams) params;
        String cmd = MessageFormat.format("if [ -x {0}/bin/polaris.sh ]; then {0}/bin/polaris.sh stop; else pkill -f polaris || true; fi", p.serviceHome(), p.confDir(), p.javaHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        PolarisParams p = (PolarisParams) params;
        String cmd = "pgrep -f 'polaris' >/dev/null";
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "polaris_server";
    }
}
