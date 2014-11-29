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

import java.io.PrintStream;
import java.util.Collection;

/**
 * Push test results to Cuanto
 *
 * @author <a href="mailto:jlecount@sungevity.com">Jason LeCount</a>
 */

public class CuantoNotifier extends Notifier {

    private String testProjectName;
    private String resultsPattern;

    @DataBoundConstructor
    public CuantoNotifier(String testProjectName, String resultsPattern) {
        this.testProjectName = testProjectName;
        this.resultsPattern = resultsPattern;
    }

    private TestRun createNewTestRun(AbstractBuild<?, ?> build, hudson.tasks.junit.TestResult junitTestResult) {
        TestRun testRun = new TestRun(build.getTime());
        testRun.addTestProperty("stderr", junitTestResult.getStderr());
        testRun.addTestProperty("stdout", junitTestResult.getStdout());
        return testRun;
    }

    private hudson.tasks.junit.TestResult parseJUnitResults(
            AbstractBuild<?,?> build,
            Launcher launcher,
            BuildListener listener) throws Exception {

        JUnitParser p = new JUnitParser(true);
        return p.parse(resultsPattern, build, launcher, listener);
    }

    private void addOutcomesForResults(TestRun theTestRun,
                                       CuantoConnector cuanto,
                                       PrintStream logger,
                                       Collection<? extends hudson.tasks.test.TestResult> results,
                                       TestResult ofResultType) {

        for (hudson.tasks.test.TestResult tr: results) {
            TestOutcome outcome = TestOutcome.newInstance(tr.getDisplayName(), tr.getId(), ofResultType);
            outcome.setDuration((long) tr.getDuration());
            logger.println("Adding outcome: " + outcome + " of type " + ofResultType);
            cuanto.addTestOutcome(outcome, theTestRun);
        }
    }

    private TestRun constructTestRun(CuantoConnector cuanto,
                                     final AbstractBuild<?, ?> build,
                                     final Launcher launcher,
                                     final BuildListener listener) throws Exception {

        hudson.tasks.junit.TestResult junitTestResult = parseJUnitResults(build, launcher, listener);

        TestRun theTestRun = createNewTestRun(build, junitTestResult);
        cuanto.addTestRun(theTestRun);

        addOutcomesForResults(theTestRun, cuanto, listener.getLogger(), junitTestResult.getPassedTests(), TestResult.Pass);
        addOutcomesForResults(theTestRun, cuanto, listener.getLogger(), junitTestResult.getFailedTests(), TestResult.Fail);
        addOutcomesForResults(theTestRun, cuanto, listener.getLogger(), junitTestResult.getSkippedTests(), TestResult.Skip);

        return theTestRun;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

        CuantoConnector cuanto = CuantoConnector.newInstance(getDescriptor().getCuantoServerUrl(), testProjectName);
        try {
            TestRun theRun = constructTestRun(cuanto, build, launcher, listener);
            return true;
        } catch (Exception e) {
            handlePublishException(e, listener);
            return ! getDescriptor().getPublishFailuresFailTheBuild();
        }

    }

    private void handlePublishException(Exception e, BuildListener listener) {

        listener.getLogger().println(
                "Cuanto results plugin: Could not parse and/or store test results: " +
                        "No test run has been stored.  Details follow:"
        );
        e.printStackTrace(listener.getLogger());

        String email = getDescriptor().getEmailToNotifyUponPublishFailures();
        if ( email != null && ! email.isEmpty()) {
            notifyThatPublishFailureHappened(email, e);
        }
    }

    //TODO: implement me
    private void notifyThatPublishFailureHappened(String email, Exception e) {
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

        public String getCuantoServerUrl() {                    return cuantoServerUrl;}
        public boolean getPublishFailuresFailTheBuild() {       return publishFailuresFailTheBuild;}
        public String getEmailToNotifyUponPublishFailures() {   return emailToNotifyUponPublishFailures; }

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
