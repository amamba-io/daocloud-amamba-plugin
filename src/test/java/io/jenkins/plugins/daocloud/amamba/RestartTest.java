package io.jenkins.plugins.daocloud.amamba;

import static org.junit.Assert.*;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class RestartTest {
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Test
    public void runningPipelineKeepsAliasAfterControllerRestart() {
        story.then(j -> {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = "{\"workspace\":{\"alias\":\"original\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            try {
                SystemCredentialsProvider.getInstance()
                        .getCredentials()
                        .add(new StringCredentialsImpl(
                                CredentialsScope.SYSTEM, "platform", "", Secret.fromString("token")));
                AmambaConfiguration c = AmambaConfiguration.get();
                c.setPlatformUrl("http://127.0.0.1:" + server.getAddress().getPort());
                c.setCredentialsId("platform");
                c.setWorkspaceEnvironment(new WorkspaceEnvironmentConfiguration(true));
                WorkflowJob job =
                        j.jenkins.createProject(Folder.class, "123").createProject(WorkflowJob.class, "pipeline");
                job.setDefinition(new CpsFlowDefinition(
                        "echo \"before=${env.DCE5_WORKSPACE_NAME}\"; sleep 20; echo \"after=${env.DCE5_WORKSPACE_NAME}\"",
                        true));
                var run = job.scheduleBuild2(0).waitForStart();
                j.waitForMessage("before=original", run);
                j.waitForMessage("Sleeping", run);
            } finally {
                server.stop(0);
            }
        });
        story.then(j -> {
            var run = j.jenkins
                    .getItemByFullName("123/pipeline", WorkflowJob.class)
                    .getBuildByNumber(1);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogContains("after=original", run);
            assertTrue(AmambaConfiguration.get().getWorkspaceEnvironment().isEnabled());
        });
    }
}
