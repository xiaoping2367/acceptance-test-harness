/*
 * The MIT License
 *
 * Copyright (c) 2014 Ericsson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package plugins;

import org.apache.commons.io.IOUtils;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.gerrit_trigger.GerritTriggerEnv;
import org.jenkinsci.test.acceptance.plugins.gerrit_trigger.GerritTriggerJob;
import org.jenkinsci.test.acceptance.plugins.gerrit_trigger.GerritTriggerNewServer;
import org.jenkinsci.test.acceptance.plugins.gerrit_trigger.GerritTriggerServer;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Set these (data) at mvn-test command line to use this test:<br>
 * <br>
 * - gtGerrituser=companion<br>
 * - gtHostname=gerrit.company.com<br>
 * - gtNoProxyForHost=set (optional)<br>
 * - gtProject=changed/by/this/test<br>
 * - gtUserhome=/home/companion<br>
 * Plus,<br>
 * - gtUserhome/.netrc shall point to that gtHostname with gtGerrituser/pwd<br>
 *
 * @author Marco.Miller@ericsson.com
 */
@WithPlugins("gerrit-trigger")
public class GerritTriggerTest extends AbstractJUnitTest {
    private static final Logger LOGGER = Logger.getLogger(GerritTriggerTest.class.getName());

    @Before
    public void setUpLogger() {
        LOGGER.setLevel(Level.ALL);
        LOGGER.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.ALL);
        LOGGER.addHandler(handler);
    }

    /**
     * Scenario: Gerrit has its Change review flags checked after Jenkins set them-<br>
     * Given a Jenkins instance that is either test-default or type=existing<br>
     * And a gerrit-trigger plugin that is either test-default or pre-installed<br>
     * And an existing Gerrit instance configured in that Jenkins<br>
     * When I push a Change that builds successfully for review<br>
     * Then Jenkins does build it successfully indeed<br>
     * And Jenkins sets the Change review flags accordingly towards Gerrit<br>
     * And Gerrit then consider these flags as checked.
     */
    @Test
    public void gerrit_has_review_flags_checked_after_jenkins_set_them() {
        assumeTrue(new File(GerritTriggerEnv.get().getUserHome(),".netrc").exists());

        GerritTriggerNewServer newServer = new GerritTriggerNewServer(jenkins);
        newServer.saveNewTestServerConfigIfNone();
        GerritTriggerServer server = new GerritTriggerServer(jenkins);
        server.saveTestServerConfig();

        String jobName = this.getClass().getCanonicalName();
        jenkins.jobs.create(FreeStyleJob.class, jobName);//no harm if existing
        GerritTriggerJob job = new GerritTriggerJob(jenkins, jobName);
        job.saveTestJobConfig();
        try {
            String changeId = pushChangeForReview(jobName);
            sleep(10000);
            String rev = stringFrom(curl(changeId));
            logCurlHttpCodeIssues(rev);
            assertThat(Integer.parseInt(valueFrom(rev, ".+Verified\":\\{\"all\":\\[\\{\"value\":(\\d).+")), is(equalTo(1)));
            assertThat(Integer.parseInt(valueFrom(rev, ".+Code-Review\":\\{\"all\":\\[\\{\"value\":(\\d).+")), is(equalTo(1)));
        }
        catch(InterruptedException|IOException e) {
            fail(e.getMessage());
        }
    }

    private String pushChangeForReview(String jobName) throws InterruptedException,IOException {
        File dir = File.createTempFile("jenkins","git");
        dir.delete();//result !needed
        assertTrue(dir.mkdir());
        String user = GerritTriggerEnv.get().getGerritUser();
        String hostName = GerritTriggerEnv.get().getHostName();
        String project = GerritTriggerEnv.get().getProject();

        assertThat(logProcessBuilderIssues(new ProcessBuilder("git", "clone", "ssh://" + user + "@" + hostName + ":29418/" + project, jobName).directory(dir).start(), "git clone"), is(equalTo(0)));

        File file = new File(dir+"/"+jobName,jobName);
        file.delete();//result !needed
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(String.valueOf(System.currentTimeMillis()));
        writer.close();
        dir = file.getParentFile();

        assertThat(logProcessBuilderIssues(new ProcessBuilder("git", "add", jobName ).directory(dir).start(), "git add"), is(equalTo(0)));
        assertThat(logProcessBuilderIssues(new ProcessBuilder("scp", "-p", "-P", "29418", user + "@" + hostName + ":hooks/commit-msg", ".git/hooks/").directory(dir).start(), "scp"), is(equalTo(0)));
        assertThat(logProcessBuilderIssues(new ProcessBuilder("git", "commit", "-m", jobName).directory(dir).start(), "git commit"), is(equalTo(0)));
        assertThat(logProcessBuilderIssues(new ProcessBuilder("git", "push", "origin", "HEAD:refs/for/master").directory(dir).start(), "git push"), is(equalTo(0)));

        Process gitLog1 = new ProcessBuilder("git","log","-1").directory(dir).start();
        logProcessBuilderIssues(gitLog1, "git log");
        return valueFrom(stringFrom(gitLog1),".+Change-Id:(.+)");
    }

    private Process curl(String changeId) throws IOException, InterruptedException {
        String hN = GerritTriggerEnv.get().getHostName();
        Process curlProcess;
        if(GerritTriggerEnv.get().getNoProxy()) {
            curlProcess = new ProcessBuilder("curl","-w","%{http_code}","--noproxy",hN,"-n","https://"+hN+"/a/changes/"+changeId+"/revisions/current/review").start();
            logProcessBuilderIssues(curlProcess, "curl");
            return curlProcess;
        }
        curlProcess = new ProcessBuilder("curl","-n","https://"+hN+"/a/changes/"+changeId+"/revisions/current/review").start();
        logProcessBuilderIssues(curlProcess, "curl");
        return curlProcess;
    }

    private String stringFrom(Process curl) throws InterruptedException, IOException {
        assertThat(curl.waitFor(), is(equalTo(0)));
        StringWriter writer = new StringWriter();
        IOUtils.copy(curl.getInputStream(), writer);
        String string = writer.toString().replaceAll(System.getProperty("line.separator"), "").replaceAll(" ", "");
        writer.close();
        return string;
    }

    private String valueFrom(String source, String regexWithGroup) {
        String value = null;
        Matcher m = Pattern.compile(regexWithGroup).matcher(source);
        if (m.matches()) {
            value = m.group(1);
        }
        assertThat(value, is(not(nullValue())));
        return value;
    }

    private int logProcessBuilderIssues(Process processToRun, String commandName) throws InterruptedException, IOException {
        int result = processToRun.waitFor();
        if (result != 0) {
            StringWriter writer = new StringWriter();
            IOUtils.copy(processToRun.getErrorStream(), writer);
            LOGGER.severe("Issue occurred during command \"" + commandName + "\":\n" + writer.toString());
            writer.close();
        }
        return result;
    }

    private void logCurlHttpCodeIssues(String curlResponse) {
        String responseCode = curlResponse.substring(curlResponse.length() - 3, curlResponse.length());
        if (!responseCode.matches("2[0-9][0-9]")) {
            LOGGER.severe("Issue occurred during curl command. Returned an erroneous http response code: " + responseCode + ".\n"
                    + "Expected 2XX response code.");
        }
    }
}
