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
package de.tracetronic.jenkins.plugins.ecutest.report.ta;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.remoting.Callable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jenkins.security.MasterToSlaveCallable;
import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.AnalysisEnvironment;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.AnalysisExecutionInfo;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComClient;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComException;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComProperty;

/**
 * Class providing the execution of trace analyses.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class TraceAnalysisRunner {

    /**
     * Runs the trace analysis.
     *
     * @param analysisFiles
     *            the analysis files
     * @param createReportDir
     *            specifies whether to create a new report directory
     * @param timeout
     *            the timeout
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return the list of successfully generated analysis reports
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             the interrupted exception
     */
    public List<FilePath> runAnalysis(final List<FilePath> analysisFiles, final boolean createReportDir,
            final int timeout,
            final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new TraceAnalysisCallable(analysisFiles, createReportDir, timeout, listener));
    }

    /**
     * Merges the analysis reports into the main report.
     *
     * @param mainReport
     *            the main report
     * @param reportFiles
     *            the analysis report files to merge
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if merge was successful, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             the interrupted exception
     */
    public boolean mergeReports(final FilePath mainReport, final List<FilePath> reportFiles,
            final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        return launcher.getChannel().call(new MergeReportsCallable(mainReport, reportFiles, listener));
    }

    /**
     * {@link Callable} executing the trace analysis of job files remotely.
     */
    private static final class TraceAnalysisCallable extends MasterToSlaveCallable<List<FilePath>, IOException> {

        private static final long serialVersionUID = 1L;

        private final List<FilePath> jobFiles;
        private final boolean createReportDir;
        private final int timeout;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link TraceAnalysisCallable}.
         *
         * @param jobFiles
         *            the list of analysis files
         * @param createReportDir
         *            specifies whether to create a new report directory
         * @param timeout
         *            the timeout running each trace analysis
         * @param listener
         *            the listener
         */
        TraceAnalysisCallable(final List<FilePath> jobFiles, final boolean createReportDir,
                final int timeout, final TaskListener listener) {
            this.jobFiles = jobFiles;
            this.createReportDir = createReportDir;
            this.timeout = timeout;
            this.listener = listener;
        }

        @Override
        public List<FilePath> call() throws IOException {
            final List<FilePath> reportFiles = new ArrayList<FilePath>();
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId);
                    AnalysisEnvironment analysisEnv = (AnalysisEnvironment) comClient.getAnalysisEnvironment()) {
                for (final FilePath jobFile : jobFiles) {
                    logger.logInfo(String.format("- Running trace analysis: %s", jobFile.getRemote()));
                    final AnalysisExecutionInfo execInfo =
                            (AnalysisExecutionInfo) analysisEnv.executeJob(jobFile.getRemote(), createReportDir);
                    int tickCounter = 0;
                    final long endTimeMillis = System.currentTimeMillis() + Long.valueOf(timeout) * 1000L;
                    while ("RUNNING".equals(execInfo.getState())) {
                        if (tickCounter % 60 == 0) {
                            logger.logInfo("-- tick...");
                        }
                        if (timeout > 0 && System.currentTimeMillis() > endTimeMillis) {
                            logger.logWarn(String.format("-> Analysis execution timeout of %d seconds reached! "
                                    + "Aborting trace analysis now...", timeout));
                            execInfo.abort();
                            break;
                        }
                        Thread.sleep(1000L);
                        tickCounter++;
                    }
                    getTestInfo(execInfo, logger);
                    reportFiles.add(jobFile.child(execInfo.getReportDb()));
                }
            } catch (final ETComException | InterruptedException e) {
                logger.logError("Caught ComException: " + e.getMessage());
            }
            return reportFiles;
        }

        /**
         * Gets the information of the executed package.
         *
         * @param execInfo
         *            the execution info
         * @param logger
         *            the logger
         * @throws ETComException
         *             in case of a COM exception
         */
        private void getTestInfo(final AnalysisExecutionInfo execInfo, final TTConsoleLogger logger)
                throws ETComException {
            final String testResult = execInfo.getResult();
            logger.logInfo(String.format("-> Analysis execution completed with result: %s", testResult));
            final String testReportDir = new File(execInfo.getReportDb()).getParentFile().getAbsolutePath();
            logger.logInfo(String.format("-> Test report directory: %s", testReportDir));
        }
    }

    /**
     * {@link Callable} merging the analysis reports into the main report remotely.
     */
    private static final class MergeReportsCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final FilePath mainReport;
        private final List<FilePath> jobReports;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link MergeReportsCallable}.
         *
         * @param mainReport
         *            the main report
         * @param jobReports
         *            the job reports
         * @param listener
         *            the listener
         */
        MergeReportsCallable(final FilePath mainReport, final List<FilePath> jobReports, final TaskListener listener) {
            this.mainReport = mainReport;
            this.jobReports = jobReports;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isMerged = true;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId);
                    AnalysisEnvironment analysisEnv = (AnalysisEnvironment) comClient.getAnalysisEnvironment()) {
                final List<String> jobFiles = getJobFiles(jobReports);
                logger.logInfo(String.format("- Merging analysis reports into main report: %s",
                        mainReport.getRemote()));
                isMerged = analysisEnv.mergeJobReports(mainReport.getRemote(), jobFiles);
            } catch (final ETComException e) {
                isMerged = false;
                logger.logError("Caught ComException: " + e.getMessage());
            }
            return isMerged;
        }

        /**
         * Gets the list job files with their absolute file paths.
         *
         * @param jobReports
         *            the job reports
         * @return the list of job files
         */
        private List<String> getJobFiles(final List<FilePath> jobReports) {
            final List<String> jobFiles = new ArrayList<String>();
            for (final FilePath jobReport : jobReports) {
                jobFiles.add(jobReport.getRemote());
            }
            return jobFiles;
        }
    }
}
