package com.sungevity.jenkins.cuanto;

import cuanto.api.CuantoConnector;
import cuanto.api.TestOutcome;
import cuanto.api.TestResult;
import cuanto.api.TestRun;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.junit.JUnitParser;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.Collection;

/**
 * Push test results to Cuanto
 *
 * @author <a href="mailto:jlecount@sungevity.com">Jason LeCount</a>
 */

/*
* This plugin is essentially an adaptor for an ant task.  Not all projects (sbt projects, js projects, etc) use
* ant to build.  However, all are run via jenkins.  So, pulling the execution of the ant task into a plugin
* keeps the requirement to have ant out of a given project and back into jenkins.
*/
public class CuantoNotifier extends Notifier {

    private String testProjectName;
    private String resultsPattern;

    @DataBoundConstructor
    public CuantoNotifier(String testProjectName, String resultsPattern) {
        System.out.println("From descriptor: cuantoServerUrl: " + getDescriptor().getCuantoServerUrl());
        System.out.println("Incoming values: testProjectName: " + testProjectName);
        System.out.println("Incoming values: resultsPattern: " + resultsPattern);

        this.testProjectName = testProjectName;
        this.resultsPattern = resultsPattern;
    }

    private TestRun createNewTestRun(AbstractBuild<?, ?> build, hudson.tasks.junit.TestResult junitTestResult) {
        TestRun testRun = new TestRun(build.getTime());
        // this throws UnsupportedOperationException!
        //testRun.addTestProperty("failedSince", Integer.valueOf(junitTestResult.getFailedSince()).toString());

        testRun.addTestProperty("stderr", junitTestResult.getStderr());
        testRun.addTestProperty("stdout", junitTestResult.getStdout());
        return testRun;
    }

    hudson.tasks.junit.TestResult parseJUnitResults(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws Exception {
        JUnitParser p = new JUnitParser(true);
        return p.parse(resultsPattern, build, launcher, listener);
    }

    private TestRun constructTestRun(CuantoConnector cuanto, final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws Exception {
        hudson.tasks.junit.TestResult junitTestResult = parseJUnitResults(build, launcher, listener);

        TestRun theTestRun = createNewTestRun(build, junitTestResult);
        cuanto.addTestRun(theTestRun);

        Collection<? extends hudson.tasks.test.TestResult> passedTests = junitTestResult.getPassedTests();
        for (hudson.tasks.test.TestResult pass: passedTests) {
            TestOutcome outcome = TestOutcome.newInstance(pass.getDisplayName(), pass.getId(), TestResult.Pass);
            outcome.setDuration((long) pass.getDuration());
            cuanto.addTestOutcome(outcome, theTestRun);
        }

        Collection<? extends hudson.tasks.test.TestResult> failedTests = junitTestResult.getFailedTests();
        for (hudson.tasks.test.TestResult fail: failedTests) {
            TestOutcome outcome = TestOutcome.newInstance(fail.getDisplayName(), fail.getId(), TestResult.Fail);
            outcome.setDuration((long) fail.getDuration());
            cuanto.addTestOutcome(outcome, theTestRun);
        }

        Collection<? extends hudson.tasks.test.TestResult> skippedTests = junitTestResult.getSkippedTests();
        for (hudson.tasks.test.TestResult skip: failedTests) {
            TestOutcome outcome = TestOutcome.newInstance(skip.getDisplayName(), skip.getId(), TestResult.Skip);
            outcome.setDuration((long) skip.getDuration());
            cuanto.addTestOutcome(outcome, theTestRun);
        }
        return theTestRun;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {
        CuantoConnector cuanto = CuantoConnector.newInstance(getDescriptor().getCuantoServerUrl(), testProjectName);
        try {
            TestRun theRun = constructTestRun(cuanto, build, launcher, listener);
            return true;
        } catch (Exception e) {
            return handlePublishException(e, null);
        }

    }

    private boolean handlePublishException(Exception e, BuildListener listener) {
        listener.getLogger().println(
                "Cuanto results plugin: Could not parse and/or store test results: " +
                        "No test run has been stored.  Details follow:"
        );
        e.printStackTrace(listener.getLogger());

        String email = getDescriptor().getEmailToNotifyUponPublishFailures();
        if ( email != null && ! email.isEmpty()) {
            notifyFailure(email, e);
        }

        if (getDescriptor().getPublishFailuresFailTheBuild()) {
            return false;
        } else {
            return true;
        }
    }

    //TODO: implement me
    private void notifyFailure(String email, Exception e) {
        System.out.println("NYI: would email: " + email + " about exception: " + e);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String cuantoServerUrl;
        private boolean publishFailuresFailTheBuild = false;
        private String emailToNotifyUponPublishFailures;

        public String getCuantoServerUrl() { return cuantoServerUrl;}
        public boolean getPublishFailuresFailTheBuild() { return publishFailuresFailTheBuild;}
        public String getEmailToNotifyUponPublishFailures() { return emailToNotifyUponPublishFailures; }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Push test results to Cuanto";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.cuantoServerUrl = json.getString("cuantoServerUrl");
            this.publishFailuresFailTheBuild = json.getBoolean("publishFailuresFailTheBuild");
            this.emailToNotifyUponPublishFailures = json.getString("emailToNotifyUponPublishFailures");
            save();
            return true;
        }

    }

}
