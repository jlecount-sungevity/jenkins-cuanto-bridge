package com.sungevity.jenkins.cuanto;

import cuanto.api.*;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.SuiteResult;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.PrintStream;
import java.util.*;

/**
 * Push test results to Cuanto
 *
 * @author <a href="mailto:jlecount@sungevity.com">Jason LeCount</a>
 */
public class CuantoNotifier extends Notifier {

    private final String testType;
    private final String resultsPattern;
    private final String testProjectName;

    @DataBoundConstructor
    public CuantoNotifier(String testType, String resultsPattern, String testProjectName) {
        this.testType = testType;
        this.resultsPattern = resultsPattern;
        this.testProjectName = testProjectName;
    }


    public String getTestType() {
        return testType;
    }

    public String getResultsPattern() {
        return resultsPattern;
    }

    public String getTestProjectName() {
        return testProjectName;
    }

    private TestRun createNewTestRun(AbstractBuild<?, ?> build, String projectKey, hudson.tasks.junit.TestResult junitTestResult) {
        TestRun testRun = new TestRun(projectKey);

        testRun.addLink("jenkins build", build.getAbsoluteUrl());

        /*
            TODO:
            add all of these or filter out some?
            How do we tell the difference between ones we want and those we don't?
            Make sure this has REPO, GIT_COMMIT and branch!  Otherwise, find and grab those too.
         */
        Map<String, String> variables = build.getBuildVariables();
        for (String key: variables.keySet()) {
            testRun.addTestProperty(key, variables.get(key));
        }

        return testRun;
    }

    private hudson.tasks.junit.TestResult parseJUnitResults(
            AbstractBuild<?,?> build,
            Launcher launcher,
            BuildListener listener) throws Exception {

        JUnitParser p = new JUnitParser(true);
        return p.parse(resultsPattern, build, launcher, listener);
    }

    private void addOutcomesForResult(TestRun theTestRun,
                                       CuantoConnector cuanto,
                                       PrintStream logger,
                                       hudson.tasks.test.TestResult testResult,
                                       TestResult status) {
        TestOutcome outcome = TestOutcome.newInstance(testResult.getDisplayName(), testResult.getId(), status);
        //FIXME: add stderr somewhere....
        outcome.setTestOutput(testResult.getStdout());
        outcome.setDuration((long) testResult.getDuration());
        logger.println("Adding outcome: " + outcome + " of type " + status);
        cuanto.addTestOutcome(outcome, theTestRun);

    }
    private void addOutcomesForResults(TestRun theTestRun,
                                       CuantoConnector cuanto,
                                       PrintStream logger,
                                       CaseResult cr) {
        for (hudson.tasks.test.TestResult tr: cr.getPassedTests()) {
            addOutcomesForResult(theTestRun, cuanto, logger, tr, TestResult.Pass);
        }

        for (hudson.tasks.test.TestResult tr: cr.getSkippedTests()) {
            addOutcomesForResult(theTestRun, cuanto, logger, tr, TestResult.Skip);
        }

        for (hudson.tasks.test.TestResult tr: cr.getFailedTests()) {
            addOutcomesForResult(theTestRun, cuanto, logger, tr, TestResult.Fail);
        }
    }

    private String getProjectKey(CuantoConnector cuanto) {
        String key = null;
        for ( Project p: cuanto.getAllProjects()) {
            if ( p.getName().equalsIgnoreCase(testProjectName)) {
                key = p.getProjectKey();
            }
        }
        return key;
    }

    private TestRun constructTestRun(CuantoConnector cuanto,
                                     final AbstractBuild<?, ?> build,
                                     final Launcher launcher,
                                     final BuildListener listener) throws Exception {

        hudson.tasks.junit.TestResult junitTestResult = parseJUnitResults(build, launcher, listener);

        String projectKey = getProjectKey(cuanto);
        TestRun theTestRun = createNewTestRun(build, projectKey, junitTestResult);
        theTestRun.setDateExecuted(new Date());
        cuanto.addTestRun(theTestRun);

        for (SuiteResult sr: junitTestResult.getSuites()) {
            for (CaseResult cr: sr.getCases()) {
                addOutcomesForResults(theTestRun, cuanto, listener.getLogger(), cr);
            }
        }

        return theTestRun;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) {

        // OK, this is nuts, but I can't get the project key without cuanto and I can't post to the cuanto instance
        // that is constructed without the key!!
        CuantoConnector cuanto = CuantoConnector.newInstance(getDescriptor().getCuantoServerUrl());
        String projectKey = getProjectKey(cuanto);
        cuanto = CuantoConnector.newInstance(getDescriptor().getCuantoServerUrl(), projectKey);

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


        private ListBoxModel createSelection(List<String> choices) {
            ListBoxModel m = new ListBoxModel();
            for (String s : choices) {
                m.add(s);
            }
            return m;
        }

        public String getCuantoServerUrl()                      {   return cuantoServerUrl;                     }
        public boolean getPublishFailuresFailTheBuild()         {   return publishFailuresFailTheBuild;         }
        public String getEmailToNotifyUponPublishFailures()     {   return emailToNotifyUponPublishFailures;    }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }


        public String getDisplayName() {
            return "Push test results to Cuanto";
        }

        private List<String> getCuantoProjects(CuantoConnector cuanto) {
            List<String> projects = new ArrayList<String>();
            for ( Project project: cuanto.getAllProjects()) {
                projects.add(project.getName());
            }
            return projects;
        }

        public ListBoxModel doFillTestProjectNameItems() {
            //TODO: Grab this from cuanto directly, using impl below, once cuanto is populated with real projects...
            List<String> projectList = new ArrayList<String>();
            try {
                CuantoConnector cuanto = CuantoConnector.newInstance(cuantoServerUrl);
                projectList = getCuantoProjects(cuanto);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return createSelection(projectList);
        }

        public ListBoxModel doFillTestTypeItems() {
            List<String> testTypes = Arrays.asList("Integration", "Unit", "Functional");
            return createSelection(testTypes);
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
