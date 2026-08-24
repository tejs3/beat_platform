package org.apache.bigtop.manager.stack.bigtop.v3_3_0.ranger;

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
public class RangerAdminScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return RangerSetup.configure(params);
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        RangerParams rangerParams = (RangerParams) params;
        // Prefer packaged service script when present after setup.sh; fall back to ews launcher.
        String cmd = MessageFormat.format(
                "if [ -x {0}/ews/ranger-admin-services.sh ]; then {0}/ews/ranger-admin-services.sh start; "
                        + "elif [ -x {0}/ranger-admin ]; then {0}/ranger-admin start; "
                        + "else echo 'Ranger Admin binaries present; run setup.sh before start' >&2; exit 1; fi",
                rangerParams.adminHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, rangerParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        RangerParams rangerParams = (RangerParams) params;
        String cmd = MessageFormat.format(
                "if [ -x {0}/ews/ranger-admin-services.sh ]; then {0}/ews/ranger-admin-services.sh stop; "
                        + "elif [ -x {0}/ranger-admin ]; then {0}/ranger-admin stop; else true; fi",
                rangerParams.adminHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, rangerParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        RangerParams rangerParams = (RangerParams) params;
        String cmd = MessageFormat.format(
                "pgrep -f 'org.apache.ranger.server.tomcat.EmbeddedServer' >/dev/null",
                rangerParams.adminHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, rangerParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "ranger_admin";
    }
}
