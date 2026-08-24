package org.apache.bigtop.manager.stack.bigtop.v3_3_0.knox;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.core.enums.ConfigType;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class KnoxSetup {

    public static ShellResult configure(Params params) {
        KnoxParams p = (KnoxParams) params;
        LinuxFileUtils.createDirectories(p.getLogDir(), p.user(), p.group(), Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(p.getPidDir(), p.user(), p.group(), Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(p.confDir(), p.user(), p.group(), Constants.PERMISSION_755, true);
        LinuxFileUtils.toFile(
                ConfigType.XML,
                MessageFormat.format("{0}/gateway-site.xml", p.confDir()),
                p.user(),
                p.group(),
                Constants.PERMISSION_644,
                p.site());
        log.info("Configured knox");
        return ShellResult.success();
    }
}
