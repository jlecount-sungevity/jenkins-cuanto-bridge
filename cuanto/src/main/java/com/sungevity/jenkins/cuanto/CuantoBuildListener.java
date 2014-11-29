package com.sungevity.jenkins.cuanto;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildListener;

/**
 * Created by jasonlecount on 11/28/14.
 */
public class CuantoBuildListener implements BuildListener {
    public void buildStarted(BuildEvent buildEvent) {

    }

    public void buildFinished(BuildEvent buildEvent) {

    }

    public void targetStarted(BuildEvent buildEvent) {

    }

    public void targetFinished(BuildEvent buildEvent) {

    }

    public void taskStarted(BuildEvent buildEvent) {
        System.out.println(buildEvent.getTask().getTaskName() + ": started...");
        if (buildEvent.getException() != null) {
            System.out.println("Exception: " + buildEvent.getException());
        }

    }

    public void taskFinished(BuildEvent buildEvent) {

    }

    public void messageLogged(BuildEvent buildEvent) {

    }
}
