package org.apache.bigtop.manager.stack.bigtop.v3_3_0.yarn;

import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.bigtop.v3_3_0.hadoop.HadoopSetup;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.spi.script.AbstractClientScript;
import org.apache.bigtop.manager.stack.core.spi.script.Script;

import com.google.auto.service.AutoService;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@Slf4j
@AutoService(Script.class)
public class YarnClientScript extends AbstractClientScript {

    @Override
    public ShellResult add(Params params) {
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_KEY_SKIP_LEVELS, "1");
        return super.add(params, properties);
    }

    @Override
    public ShellResult configure(Params params) {
        super.configure(params);
        return HadoopSetup.configure(params);
    }

    @Override
    public String getComponentName() {
        return "yarn_client";
    }
}
