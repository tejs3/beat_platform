package org.apache.bigtop.manager.stack.bigtop.v3_3_0.yarn;

import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopParams;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopSetup;
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
public class YarnNodeManagerScript extends AbstractServerScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return HadoopSetup.configure(params, "nodemanager");
    }

    @Override
    public ShellResult start(Params params) {
        configure(params);
        HadoopParams hadoopParams = (HadoopParams) params;
        String cmd = MessageFormat.format("{0}/yarn --daemon start nodemanager", hadoopParams.binDir());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, hadoopParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult stop(Params params) {
        HadoopParams hadoopParams = (HadoopParams) params;
        String cmd = MessageFormat.format("{0}/yarn --daemon stop nodemanager", hadoopParams.binDir());
        try {
            return LinuxOSUtils.sudoExecCmd(cmd, hadoopParams.user());
        } catch (Exception e) {
            throw new StackException(e);
        }
    }

    @Override
    public ShellResult status(Params params) {
        HadoopParams hadoopParams = (HadoopParams) params;
        return LinuxOSUtils.checkProcess(hadoopParams.getNodeManagerPidFile());
    }

    @Override
    public String getComponentName() {
        return "yarn_nodemanager";
    }
}
