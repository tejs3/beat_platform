package org.apache.bigtop.manager.stack.bigtop.v3_3_0.nifi;

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
public class NifiNodeScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return NifiSetup.configure(params);
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        NifiParams p = (NifiParams) params;
        String cmd = MessageFormat.format("export JAVA_HOME={2}; {0}/bin/nifi.sh start", p.serviceHome(), p.confDir(), p.javaHome(), p.getPidDir());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        NifiParams p = (NifiParams) params;
        String cmd = MessageFormat.format("{0}/bin/nifi.sh stop", p.serviceHome(), p.confDir(), p.javaHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        NifiParams p = (NifiParams) params;
        String cmd = "pgrep -f 'org.apache.nifi.NiFi' >/dev/null";
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "nifi_node";
    }
}
