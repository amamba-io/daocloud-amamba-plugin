package io.jenkins.plugins.daocloud.amamba;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public final class WorkspaceEnvironmentConfiguration
        extends AbstractDescribableImpl<WorkspaceEnvironmentConfiguration> {
    private final boolean enabled;

    @DataBoundConstructor
    public WorkspaceEnvironmentConfiguration(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<WorkspaceEnvironmentConfiguration> {
        @Override
        public String getDisplayName() {
            return "Workspace environment";
        }
    }
}
