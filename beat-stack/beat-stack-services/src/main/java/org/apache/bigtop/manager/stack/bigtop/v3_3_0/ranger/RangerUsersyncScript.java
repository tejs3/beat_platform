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
public class RangerUsersyncScript extends AbstractServerScript {

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
        String cmd = MessageFormat.format(
                "if [ -x {0}/ranger-usersync-services.sh ]; then {0}/ranger-usersync-services.sh start; "
                        + "elif [ -x {0}/start.sh ]; then {0}/start.sh; "
                        + "else echo 'Ranger UserSync binaries present; run setup.sh before start' >&2; exit 1; fi",
                rangerParams.usersyncHome());
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
                "if [ -x {0}/ranger-usersync-services.sh ]; then {0}/ranger-usersync-services.sh stop; "
                        + "elif [ -x {0}/stop.sh ]; then {0}/stop.sh; else true; fi",
                rangerParams.usersyncHome());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, rangerParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        RangerParams rangerParams = (RangerParams) params;
        String cmd = "pgrep -f 'org.apache.ranger.unixusersync.process' >/dev/null || pgrep -f ranger-usersync >/dev/null";
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, rangerParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public String getComponentName() {
        return "ranger_usersync";
    }
}
