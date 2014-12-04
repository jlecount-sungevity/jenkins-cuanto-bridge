package com.sungevity.jenkins.cuanto;

import cuanto.api.*;
import cuanto.api.Project;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
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

    static String[] DESIRED_JENKINS_VARS = new String[] {
            "GIT_COMMIT", "GIT_REPO", "GIT_BRANCH",
            "JOB_NAME", "USER", "BUILD_CAUSE_MANUALTRIGGER"
    };

    private final String testType;
    private final String resultsPattern;
    private final String testProjectName;

    private final String newline = System.getProperty("line.separator");

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


    private hudson.tasks.junit.TestResult parseJUnitResults(
            AbstractBuild<?,?> build,
            Launcher launcher,
            BuildListener listener) throws Exception {

        JUnitParser p = new JUnitParser(true);
        return p.parse(resultsPattern, build, launcher, listener);
    }

    private String getStdoutAndStderrFromTestResult(hudson.tasks.test.TestResult testResult) {
        StringBuffer sb = new StringBuffer();

        if ( testResult.getStdout() != null && ! testResult.getStdout().isEmpty()) {
            sb.append(testResult.getStdout());
        }

        if ( testResult.getStderr() != null && ! testResult.getStderr().isEmpty()) {
            sb.append(newline + newline);
            sb.append(testResult.getStderr());
            sb.append(newline + newline);
        }

        if ( testResult.getErrorDetails() != null && ! testResult.getErrorDetails().isEmpty()) {
            sb.append(newline);
            sb.append(testResult.getErrorDetails() + newline + newline);
        }
        if ( testResult.getErrorStackTrace() != null && ! testResult.getErrorStackTrace().isEmpty()) {
            sb.append(newline);
            sb.append(testResult.getErrorStackTrace() + newline + newline);
        }
        return sb.toString().trim();
    }

    private void addOutcomesForResult(TestRun theTestRun,
                                      AbstractBuild<?, ?> build,
                                      CuantoConnector cuanto,
                                      PrintStream logger,
                                      hudson.tasks.test.TestResult testResult,
                                      TestResult status) {
        TestOutcome outcome = TestOutcome.newInstance(testResult.getDisplayName(), testResult.getId(), status);
        if ( testResult.getOwner() != null) {
            outcome.setOwner(testResult.getOwner().toString());
        }
        String testOutput = getStdoutAndStderrFromTestResult(testResult);
        if ( testOutput != null && testOutput.length() > 0 ) {
            outcome.setTestOutput(testOutput);
        }

        addEnvVarsToOutcome(outcome, build.getEnvVars());
        outcome.setDuration((long) testResult.getDuration());
        try {
            cuanto.addTestOutcome(outcome, theTestRun);
        } catch (Exception e) {
            logger.println("Failed to add test outcome to cuanto: " + e.getMessage());
        }
    }

    private void addEnvVarsToOutcome(TestOutcome outcome, Map<String, String> envVars) {
        outcome.addLink(envVars.get("BUILD_URL"), envVars.get("BUILD_URL"));
        for (int i = 0; i < DESIRED_JENKINS_VARS.length; i++) {
            String desiredVar = DESIRED_JENKINS_VARS[i];
            String value = envVars.get(desiredVar);
            if ( value == null ) {
                value = "";
            }
            outcome.addTestProperty(desiredVar, value);
        }
    }

    private void addOutcomesForResults(TestRun theTestRun,
                                       AbstractBuild<?, ?> build,
                                       CuantoConnector cuanto,
                                       PrintStream logger,
                                       CaseResult cr) {
        for (hudson.tasks.test.TestResult tr: cr.getPassedTests()) {
            addOutcomesForResult(theTestRun, build, cuanto, logger, tr, TestResult.Pass);
        }

        for (hudson.tasks.test.TestResult tr: cr.getSkippedTests()) {
            addOutcomesForResult(theTestRun, build, cuanto, logger, tr, TestResult.Skip);
        }

        for (hudson.tasks.test.TestResult tr: cr.getFailedTests()) {
            addOutcomesForResult(theTestRun, build, cuanto, logger, tr, TestResult.Fail);
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
        TestRun theTestRun = new TestRun(new Date());
        theTestRun.setDateExecuted(new Date());
        cuanto.addTestRun(theTestRun);

        addRecentChangesets(theTestRun, build);


        theTestRun.addLink("jenkins build", build.getAbsoluteUrl());

        List<ParametersAction> actions = build.getActions(ParametersAction.class);
        for (Action action: actions) {
            String name = action.getDisplayName();
            String value = build.getBuildVariableResolver().resolve(name);
            if ( name != null && value != null) {
                listener.getLogger().println("Adding PA: " + name + ": " + value + " to testRun");
                theTestRun.addTestProperty(name, value);
            }
        }

        List<EnvironmentContributingAction> ecaActions = build.getActions(EnvironmentContributingAction.class);
        for (Action action: ecaActions) {
            String name = action.getDisplayName();
            String value = build.getBuildVariableResolver().resolve(name);
            if ( name != null && value != null ) {
                theTestRun.addTestProperty(name, value);
                listener.getLogger().println("Adding ECA: " + name + ": " + value + " to testRun");
            }
        }


        for (SuiteResult sr: junitTestResult.getSuites()) {
            for (CaseResult cr: sr.getCases()) {
                addOutcomesForResults(theTestRun, build, cuanto, listener.getLogger(), cr);
            }
        }

        return theTestRun;
    }

    private void addRecentChangesets(TestRun theTestRun, AbstractBuild<?, ?> build) {
        ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = build.getChangeSet();
        int items = changeSet.getItems().length;
        int current = 0;
        StringBuffer sb = new StringBuffer();
        for (ChangeLogSet.Entry entry : changeSet) {
            sb.append(entry.getCommitId());
            if ( current < items - 1) {
                sb.append(", ");
            }
            current++;
            System.out.println("Debug -- commit: " + entry.getCommitId());
        }

        theTestRun.addTestProperty("recent_changesets", sb.toString());
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
