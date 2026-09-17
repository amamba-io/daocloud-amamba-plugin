package io.jenkins.plugins.daocloud.amamba.environment;

import hudson.model.InvisibleAction;

/** Persisted per-build snapshot, including a failed lookup (null alias). Contains no credentials. */
public final class WorkspaceEnvironmentAction extends InvisibleAction {
    private final String alias;

    public WorkspaceEnvironmentAction(String alias) {
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
