package org.apache.bigtop.manager.stack.bigtop.v3_3_0.ranger;

import org.apache.bigtop.manager.common.constants.Constants;
import org.apache.bigtop.manager.common.shell.ShellResult;
import org.apache.bigtop.manager.stack.core.spi.param.Params;
import org.apache.bigtop.manager.stack.core.utils.linux.LinuxFileUtils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;
import java.util.Map;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RangerSetup {

    public static ShellResult configure(Params params) {
        RangerParams rangerParams = (RangerParams) params;
        String user = rangerParams.user();
        String group = rangerParams.group();

        LinuxFileUtils.createDirectories(rangerParams.getRangerLogDir(), user, group, Constants.PERMISSION_755, true);
        LinuxFileUtils.createDirectories(rangerParams.getRangerPidDir(), user, group, Constants.PERMISSION_755, true);

        Map<String, Object> admin = rangerParams.rangerAdmin();
        Map<String, Object> usersync = rangerParams.rangerUsersync();

        String adminProps = admin.getOrDefault("install_properties", defaultAdminInstallProperties(rangerParams)).toString();
        String usersyncProps =
                usersync.getOrDefault("install_properties", defaultUsersyncInstallProperties(rangerParams)).toString();

        LinuxFileUtils.toFileByTemplate(
                adminProps,
                MessageFormat.format("{0}/install.properties", rangerParams.adminHome()),
                user,
                group,
                Constants.PERMISSION_644,
                rangerParams.getGlobalParamsMap());

        LinuxFileUtils.toFileByTemplate(
                usersyncProps,
                MessageFormat.format("{0}/install.properties", rangerParams.usersyncHome()),
                user,
                group,
                Constants.PERMISSION_644,
                rangerParams.getGlobalParamsMap());

        log.info("Configured Ranger Admin + UserSync install.properties under {}", rangerParams.serviceHome());
        return ShellResult.success();
    }

    private static String defaultAdminInstallProperties(RangerParams p) {
        return "PYTHON_COMMAND_INVOKER=python3\n"
                + "DB_FLAVOR=POSTGRES\n"
                + "SQL_CONNECTOR_JAR=\n"
                + "db_root_user=postgres\n"
                + "db_root_password=\n"
                + "db_host=localhost\n"
                + "db_name=ranger\n"
                + "db_user=rangeradmin\n"
                + "db_password=rangeradmin\n"
                + "rangerAdmin_password=Admin123\n"
                + "rangerTagsync_password=Admin123\n"
                + "rangerUsersync_password=Admin123\n"
                + "keyadmin_password=Admin123\n"
                + "audit_solr_urls=\n"
                + "policymgr_external_url=http://localhost:6080\n"
                + "unix_user=" + p.user() + "\n"
                + "unix_group=" + p.group() + "\n";
    }

    private static String defaultUsersyncInstallProperties(RangerParams p) {
        return "POLICY_MGR_URL=http://localhost:6080\n"
                + "SYNC_SOURCE=unix\n"
                + "SYNC_INTERVAL=5\n"
                + "unix_user=" + p.user() + "\n"
                + "unix_group=" + p.group() + "\n"
                + "rangerUsersync_password=Admin123\n";
    }
}
