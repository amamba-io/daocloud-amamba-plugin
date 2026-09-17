package io.jenkins.plugins.daocloud.amamba.environment;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.daocloud.amamba.AmambaConfiguration;
import io.jenkins.plugins.daocloud.amamba.PlatformClient;
import java.io.IOException;
import jenkins.model.Jenkins;

@Extension
public final class WorkspaceEnvironmentContributor extends EnvironmentContributor {
    public static final String VARIABLE = "DCE5_WORKSPACE_NAME";

    @Override
    public void buildEnvironmentFor(Run run, EnvVars env, TaskListener listener)
            throws IOException, InterruptedException {
        WorkspaceEnvironmentAction snapshot;
        synchronized (run) {
            snapshot = run.getAction(WorkspaceEnvironmentAction.class);
            if (snapshot == null) {
                AmambaConfiguration config = AmambaConfiguration.get();
                if (!config.getWorkspaceEnvironment().isEnabled()) return;
                Item root = run.getParent();
                while (root.getParent() instanceof Item parent) root = parent;
                if (root.getParent() != Jenkins.get()
                        || !(root instanceof Folder)
                        || !root.getName().matches("[0-9]+")) return;
                String alias = null;
                try {
                    alias = new PlatformClient().workspaceAlias(root.getName(), config);
                } catch (IOException e) {
                    listener.getLogger()
                            .println("[DaoCloud Amamba] Workspace " + root.getName() + ": " + e.getMessage() + "; "
                                    + VARIABLE + " was not injected.");
                }
                snapshot = new WorkspaceEnvironmentAction(alias);
                run.addAction(snapshot);
                try {
                    run.save();
                } catch (IOException e) {
                    listener.getLogger().println("[DaoCloud Amamba] Could not persist workspace snapshot.");
                }
            }
        }
        if (snapshot.getAlias() != null) env.put(VARIABLE, snapshot.getAlias());
    }
}
