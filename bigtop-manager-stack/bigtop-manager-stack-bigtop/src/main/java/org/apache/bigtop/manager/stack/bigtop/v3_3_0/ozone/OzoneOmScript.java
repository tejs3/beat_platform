package org.apache.bigtop.manager.stack.bigtop.v3_3_0.ozone;

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
public class OzoneOmScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return OzoneSetup.configure(params);
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        OzoneParams p = (OzoneParams) params;
        String cmd = MessageFormat.format("export JAVA_HOME={2}; {0}/bin/ozone om --init || true; {0}/bin/ozone --daemon start om", p.serviceHome(), p.confDir(), p.javaHome(), p.getPidDir());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        OzoneParams p = (OzoneParams) params;
        String cmd = MessageFormat.format("{0}/bin/ozone --daemon stop om", p.serviceHome(), p.confDir(), p.javaHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        OzoneParams p = (OzoneParams) params;
        String cmd = "pgrep -f 'org.apache.hadoop.ozone.om.OzoneManagerStarter' >/dev/null";
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, p.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "ozone_om";
    }
}
