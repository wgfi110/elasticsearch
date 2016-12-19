/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.esnative;

import joptsimple.OptionSet;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cli.Command;
import org.elasticsearch.cli.CommandTestCase;
import org.elasticsearch.cli.MockTerminal;
import org.elasticsearch.cli.Terminal.Verbosity;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.security.authz.permission.FieldPermissions;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;

/**
 * Unit tests for the {@code ESNativeRealmMigrateTool}
 */
public class ESNativeRealmMigrateToolTests extends CommandTestCase {

    @Override
    protected Command newCommand() {
        return new ESNativeRealmMigrateTool();
    }

    public void testUserJson() throws Exception {
        assertThat(ESNativeRealmMigrateTool.MigrateUserOrRoles.createUserJson(Strings.EMPTY_ARRAY, "hash".toCharArray()),
                equalTo("{\"password_hash\":\"hash\",\"roles\":[]}"));
        assertThat(ESNativeRealmMigrateTool.MigrateUserOrRoles.createUserJson(new String[]{"role1", "role2"}, "hash".toCharArray()),
                equalTo("{\"password_hash\":\"hash\",\"roles\":[\"role1\",\"role2\"]}"));
    }

    public void testRoleJson() throws Exception {
        RoleDescriptor.IndicesPrivileges ip = RoleDescriptor.IndicesPrivileges.builder()
                .indices(new String[]{"i1", "i2", "i3"})
                .privileges(new String[]{"all"})
                .fieldPermissions(new FieldPermissions(new String[]{"body"}, null))
                .build();
        RoleDescriptor.IndicesPrivileges[] ips = new RoleDescriptor.IndicesPrivileges[1];
        ips[0] = ip;
        String[] cluster = Strings.EMPTY_ARRAY;
        String[] runAs = Strings.EMPTY_ARRAY;
        RoleDescriptor rd = new RoleDescriptor("rolename", cluster, ips, runAs);
        assertThat(ESNativeRealmMigrateTool.MigrateUserOrRoles.createRoleJson(rd),
                equalTo("{\"cluster\":[],\"indices\":[{\"names\":[\"i1\",\"i2\",\"i3\"]," +
                                "\"privileges\":[\"all\"],\"field_security\":{\"grant\":[\"body\"]}}],\"run_as\":[],\"metadata\":{}}"));
    }

    public void testTerminalLogger() throws Exception {
        Logger terminalLogger = ESNativeRealmMigrateTool.getTerminalLogger(terminal);
        assertThat(terminal.getOutput(), isEmptyString());

        // only error and fatal gets logged at normal verbosity
        terminal.setVerbosity(Verbosity.NORMAL);
        List<Level> nonLoggingLevels = new ArrayList<>(Arrays.asList(Level.values()));
        nonLoggingLevels.removeAll(Arrays.asList(Level.ERROR, Level.FATAL));
        for (Level level : nonLoggingLevels) {
            terminalLogger.log(level, "this level should not log " + level.name());
            assertThat(terminal.getOutput(), isEmptyString());
        }

        terminalLogger.log(Level.ERROR, "logging an error");
        assertEquals("logging an error" + System.lineSeparator(), terminal.getOutput());
        terminal.reset();
        assertThat(terminal.getOutput(), isEmptyString());

        terminalLogger.log(Level.FATAL, "logging a fatal message");
        assertEquals("logging a fatal message" + System.lineSeparator(), terminal.getOutput());
        terminal.reset();
        assertThat(terminal.getOutput(), isEmptyString());

        // everything will get logged at verbose!
        terminal.setVerbosity(Verbosity.VERBOSE);
        List<Level> loggingLevels = new ArrayList<>(Arrays.asList(Level.values()));
        loggingLevels.remove(Level.OFF);
        for (Level level : loggingLevels) {
            terminalLogger.log(level, "this level should log " + level.name());
            assertEquals("this level should log " + level.name() + System.lineSeparator(), terminal.getOutput());
            terminal.reset();
            assertThat(terminal.getOutput(), isEmptyString());
        }
    }

    public void testMissingFiles() throws Exception {
        Path homeDir = createTempDir();
        Path confDir = homeDir.resolve("config");
        Path xpackConfDir = confDir.resolve("x-pack");
        Files.createDirectories(xpackConfDir);

        ESNativeRealmMigrateTool.MigrateUserOrRoles muor = new ESNativeRealmMigrateTool.MigrateUserOrRoles();
        OptionSet options = muor.getParser().parse("-u", "elastic", "-p", "changeme", "-U", "http://localhost:9200");
        Settings settings = Settings.builder()
                .put("path.home", homeDir)
                .put("path.conf", confDir)
                .build();
        Environment environment = new Environment(settings);
        MockTerminal mockTerminal = new MockTerminal();

        FileNotFoundException fnfe = expectThrows(FileNotFoundException.class,
                () -> muor.importUsers(mockTerminal, environment, options));
        assertThat(fnfe.getMessage(), containsString("users file"));

        Files.createFile(xpackConfDir.resolve("users"));
        fnfe = expectThrows(FileNotFoundException.class,
                () -> muor.importUsers(mockTerminal, environment, options));
        assertThat(fnfe.getMessage(), containsString("users_roles file"));

        fnfe = expectThrows(FileNotFoundException.class,
                () -> muor.importRoles(mockTerminal, environment, options));
        assertThat(fnfe.getMessage(), containsString("roles.yml file"));
    }
}
