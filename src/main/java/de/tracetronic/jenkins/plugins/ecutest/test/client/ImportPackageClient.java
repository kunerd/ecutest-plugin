/*
 * Copyright (c) 2015-2018 TraceTronic GmbH
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice, this
 *      list of conditions and the following disclaimer.
 *
 *   2. Redistributions in binary form must reproduce the above copyright notice, this
 *      list of conditions and the following disclaimer in the documentation and/or
 *      other materials provided with the distribution.
 *
 *   3. Neither the name of TraceTronic GmbH nor the names of its
 *      contributors may be used to endorse or promote products derived from
 *      this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.tracetronic.jenkins.plugins.ecutest.test.client;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.remoting.Callable;

import java.io.IOException;

import jenkins.security.MasterToSlaveCallable;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import de.tracetronic.jenkins.plugins.ecutest.ETPlugin.ToolVersion;
import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ImportPackageAttributeConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ImportPackageConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ImportPackageDirConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.TMSConfig;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComClient;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComException;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComProperty;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.TestManagement;

/**
 * Client to import ECU-TEST packages via COM interface.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class ImportPackageClient extends AbstractTMSClient {

    /**
     * Defines the minimum required ECU-TEST version for this client to work properly.
     */
    private static final ToolVersion ET_MIN_VERSION = new ToolVersion(6, 6, 0, 0);

    private final TMSConfig importConfig;

    /**
     * Instantiates a new {@link ImportPackageClient}.
     *
     * @param importConfig
     *            the import configuration
     */
    public ImportPackageClient(final TMSConfig importConfig) {
        this.importConfig = importConfig;
    }

    /**
     * @return the import package configuration
     */
    public TMSConfig getImportConfig() {
        return importConfig;
    }

    /**
     * Imports a package according to given import configuration.
     *
     * @param project
     *            the project
     * @param workspace
     *            the workspace
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if successful, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public boolean importPackage(final Item project, final FilePath workspace, final Launcher launcher,
            final TaskListener listener) throws IOException, InterruptedException {
        boolean isImported = false;
        if (isCompatible(ET_MIN_VERSION, workspace, launcher, listener)) {
            try {
                final StandardUsernamePasswordCredentials credentials = ((ImportPackageConfig) importConfig)
                        .getCredentials(project);
                if (login(credentials, launcher, listener)) {
                    if (importConfig instanceof ImportPackageDirConfig) {
                        isImported = importPackageDirFromTMS(launcher, listener);
                    } else if (importConfig instanceof ImportPackageConfig) {
                        isImported = importPackageFromTMS(launcher, listener);
                    }
                }
            } finally {
                logout(launcher, listener);
            }
        }
        return isImported;
    }

    /**
     * Imports a package attributes according to given import configuration.
     *
     * @param project
     *            the project
     * @param workspace
     *            the workspace
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if successful, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public boolean importPackageAttributes(final Item project, final FilePath workspace, final Launcher launcher,
            final TaskListener listener) throws IOException, InterruptedException {
        boolean isImported = false;
        if (isCompatible(ET_MIN_VERSION, workspace, launcher, listener)) {
            try {
                final StandardUsernamePasswordCredentials credentials = ((ImportPackageAttributeConfig) importConfig)
                        .getCredentials(project);
                if (login(credentials, launcher, listener)) {
                    isImported = importPackageAttributesFromTMS(launcher, listener);
                }
            } finally {
                logout(launcher, listener);
            }
        }
        return isImported;
    }

    /**
     * Imports a package from test management service.
     *
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true}, if import succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private boolean importPackageFromTMS(final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new ImportPackageCallable((ImportPackageConfig) importConfig, listener));
    }

    /**
     * Imports a package directory from test management service.
     *
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true}, if import succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private boolean importPackageDirFromTMS(final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new ImportPackageDirCallable((ImportPackageDirConfig) importConfig, listener));
    }

    /**
     * Imports a package attributes from test management service.
     *
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true}, if import succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private boolean importPackageAttributesFromTMS(final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new ImportPackageAttributeCallable((ImportPackageAttributeConfig) importConfig, listener));
    }

    /**
     * {@link Callable} providing remote access to import a package from test management system via COM.
     */
    private static final class ImportPackageCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final ImportPackageConfig importConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link ImportPackageCallable}.
         *
         * @param importConfig
         *            the import configuration
         * @param listener
         *            the listener
         */
        ImportPackageCallable(final ImportPackageConfig importConfig, final TaskListener listener) {
            this.importConfig = importConfig;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isImported = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo(String.format("- Importing package %s from test management system...",
                    importConfig.getTmsPath()));
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestManagement tm = (TestManagement) comClient.getTestManagement();
                if (isImported = tm.importPackage(importConfig.getTmsPath(), importConfig.getImportPath(),
                        importConfig.getParsedTimeout())) {
                    logger.logInfo(String.format("-> Package imported successfully to target directory %s.",
                            importConfig.getImportPath()));
                }
            } catch (final ETComException e) {
                logger.logError("-> Importing package failed: " + e.getMessage());
            }
            return isImported;
        }
    }

    /**
     * {@link Callable} providing remote access to import a package directory from test management system via COM.
     */
    private static final class ImportPackageDirCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final ImportPackageDirConfig importConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link ImportPackageCallable}.
         *
         * @param importConfig
         *            the import configuration
         * @param listener
         *            the listener
         */
        ImportPackageDirCallable(final ImportPackageDirConfig importConfig, final TaskListener listener) {
            this.importConfig = importConfig;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isImported = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo(String.format("- Importing package directory %s from test management system...",
                    importConfig.getTmsPath()));
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestManagement tm = (TestManagement) comClient.getTestManagement();
                isImported = tm.importPackageDirectory(importConfig.getTmsPath(), importConfig.getImportPath(),
                        importConfig.getParsedTimeout());
                logger.logInfo(String.format("-> Package directory imported successfully to target directory %s.",
                        importConfig.getImportPath()));
            } catch (final ETComException e) {
                logger.logError("-> Importing package directory failed: " + e.getMessage());
            }
            return isImported;
        }
    }

    /**
     * {@link Callable} providing remote access to import a package attributes from test management system via COM.
     */
    private static final class ImportPackageAttributeCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final ImportPackageAttributeConfig importConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link ImportPackageCallable}.
         *
         * @param importConfig
         *            the import configuration
         * @param listener
         *            the listener
         */
        ImportPackageAttributeCallable(final ImportPackageAttributeConfig importConfig, final TaskListener listener) {
            this.importConfig = importConfig;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isImported = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo(String.format("- Importing attributes of package %s from test management system...",
                    importConfig.getFilePath()));
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestManagement tm = (TestManagement) comClient.getTestManagement();
                isImported = tm.importPackageAttributes(importConfig.getFilePath(), importConfig.getParsedTimeout());
                logger.logInfo("-> Package attributes imported successfully.");
            } catch (final ETComException e) {
                logger.logError("-> Importing package attributes failed: " + e.getMessage());
            }
            return isImported;
        }
    }
}
