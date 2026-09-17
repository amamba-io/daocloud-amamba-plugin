package io.jenkins.plugins.daocloud.amamba;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

@Extension
@Symbol("daocloudAmamba")
public final class AmambaConfiguration extends GlobalConfiguration {
    private String platformUrl = "";
    private String credentialsId = "";
    private int requestTimeoutSeconds = 5;
    private WorkspaceEnvironmentConfiguration workspaceEnvironment = new WorkspaceEnvironmentConfiguration(false);

    public AmambaConfiguration() {
        load();
    }

    public static AmambaConfiguration get() {
        return GlobalConfiguration.all().get(AmambaConfiguration.class);
    }

    @Override
    public String getDisplayName() {
        return "DaoCloud Amamba";
    }

    public String getPlatformUrl() {
        return platformUrl;
    }

    @DataBoundSetter
    public void setPlatformUrl(String value) {
        platformUrl = value == null ? "" : value.trim();
        save();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String value) {
        credentialsId = value == null ? "" : value.trim();
        save();
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    @DataBoundSetter
    public void setRequestTimeoutSeconds(int value) {
        if (value < 1 || value > 300) throw new IllegalArgumentException("Timeout must be between 1 and 300 seconds");
        requestTimeoutSeconds = value;
        save();
    }

    public WorkspaceEnvironmentConfiguration getWorkspaceEnvironment() {
        return workspaceEnvironment;
    }

    @DataBoundSetter
    public void setWorkspaceEnvironment(WorkspaceEnvironmentConfiguration value) {
        workspaceEnvironment = value == null ? new WorkspaceEnvironmentConfiguration(false) : value;
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        req.bindJSON(this, json);
        save();
        return true;
    }
}
