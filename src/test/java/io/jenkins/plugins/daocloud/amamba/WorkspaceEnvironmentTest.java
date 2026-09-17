package io.jenkins.plugins.daocloud.amamba;

import static org.junit.Assert.*;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.EnvVars;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.daocloud.amamba.environment.WorkspaceEnvironmentAction;
import io.jenkins.plugins.daocloud.amamba.environment.WorkspaceEnvironmentContributor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class WorkspaceEnvironmentTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"workspace\":{\"alias\":\"研发 Team A\"}}";
    private volatile long delay;

    @Before
    public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.SYSTEM, "platform", "", Secret.fromString("test-secret-token")));
        AmambaConfiguration c = AmambaConfiguration.get();
        c.setPlatformUrl("http://127.0.0.1:" + server.getAddress().getPort());
        c.setCredentialsId("platform");
        c.setWorkspaceEnvironment(new WorkspaceEnvironmentConfiguration(true));
    }

    @After
    public void stop() {
        if (server != null) server.stop(0);
    }

    private WorkflowJob job(String folderName, String script) throws Exception {
        Folder folder = j.jenkins.getItemByFullName(folderName, Folder.class);
        if (folder == null) folder = j.jenkins.createProject(Folder.class, folderName);
        WorkflowJob job = folder.createProject(
                WorkflowJob.class, "pipeline" + folder.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    @Test
    public void pipelineAndShellSnapshotAndRename() throws Exception {
        WorkflowJob job = job(
                "123", "node { echo env.DCE5_WORKSPACE_NAME; sh 'printf \"shell=%s\\n\" \"$DCE5_WORKSPACE_NAME\"' }");
        WorkflowRun first = j.buildAndAssertSuccess(job);
        j.assertLogContains("shell=研发 Team A", first);
        assertEquals("/apis/ghippo.io/v1alpha1/workspaces/123", requestPath.get());
        assertEquals("Bearer test-secret-token", authorization.get());
        assertEquals(1, requests.get());
        body = "{\"workspace\":{\"alias\":\"renamed\"}}";
        assertEquals(
                "研发 Team A", first.getEnvironment(TaskListener.NULL).get(WorkspaceEnvironmentContributor.VARIABLE));
        first.reload();
        assertEquals(
                "研发 Team A", first.getEnvironment(TaskListener.NULL).get(WorkspaceEnvironmentContributor.VARIABLE));
        assertEquals(1, requests.get());
        WorkflowRun next = j.buildAndAssertSuccess(job);
        j.assertLogContains("shell=renamed", next);
        assertEquals(2, requests.get());
        assertFalse(JenkinsRule.getLog(first).contains("test-secret-token"));
    }

    @Test
    public void nestedJobsAndRemoteCause() throws Exception {
        Folder root = j.jenkins.createProject(Folder.class, "456");
        var branches =
                root.createProject(org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject.class, "repo");
        WorkflowJob job = branches.getProjectFactory()
                .newInstance(new jenkins.branch.Branch(
                        "test",
                        new jenkins.scm.api.SCMHead("feature/test"),
                        new hudson.scm.NullSCM(),
                        java.util.Collections.emptyList()));
        job.save();
        branches.onLoad(root, "repo");
        job = branches.getItem("feature%2Ftest");
        job.setDefinition(new CpsFlowDefinition("echo env.DCE5_WORKSPACE_NAME", true));
        WorkflowRun run = job.scheduleBuild2(
                        0, new hudson.model.CauseAction(new Cause.RemoteCause("127.0.0.1", "webhook")))
                .get();
        j.assertBuildStatus(Result.SUCCESS, run);
        j.assertLogContains("研发 Team A", run);
        assertEquals("/apis/ghippo.io/v1alpha1/workspaces/456", requestPath.get());
    }

    @Test
    public void unmanagedAndDisabledJobsDoNotQuery() throws Exception {
        j.buildAndAssertSuccess(job("ordinary", "echo 'ok'"));
        WorkflowJob root = j.createProject(WorkflowJob.class, "root");
        root.setDefinition(new CpsFlowDefinition("echo 'ok'", true));
        j.buildAndAssertSuccess(root);
        AmambaConfiguration.get().setWorkspaceEnvironment(new WorkspaceEnvironmentConfiguration(false));
        j.buildAndAssertSuccess(job("123", "echo 'ok'"));
        assertEquals(0, requests.get());
    }

    @Test
    public void failuresContinueAndAreMemoized() throws Exception {
        WorkflowJob job = job("123", "echo \"alias=${env.DCE5_WORKSPACE_NAME}\"");
        for (int code : new int[] {401, 403, 404, 500, 302}) {
            status = code;
            WorkflowRun run = j.buildAndAssertSuccess(job);
            j.assertLogContains("was not injected", run);
            assertNotNull(run.getAction(WorkspaceEnvironmentAction.class));
            assertNull(run.getEnvironment(TaskListener.NULL).get(WorkspaceEnvironmentContributor.VARIABLE));
        }
        assertEquals(5, requests.get());
        status = 200;
        for (String invalid :
                new String[] {"invalid", "{}", "{\"workspace\":{\"alias\":\"\"}}", "{\"workspace\":{\"alias\":123}}"}) {
            body = invalid;
            j.assertLogContains("was not injected", j.buildAndAssertSuccess(job));
        }
        assertEquals(9, requests.get());
        AmambaConfiguration.get().setCredentialsId("missing");
        j.assertLogContains("credential is missing", j.buildAndAssertSuccess(job));
        assertEquals(9, requests.get());
    }

    @Test
    public void requestTimeoutContinuesBuild() throws Exception {
        delay = 1500;
        AmambaConfiguration.get().setRequestTimeoutSeconds(1);
        j.assertLogContains("timed out", j.buildAndAssertSuccess(job("123", "echo 'ok'")));
        assertEquals(1, requests.get());
    }

    @Test
    public void persistedSnapshotAndParallelReads() throws Exception {
        AmambaConfiguration.get().setWorkspaceEnvironment(new WorkspaceEnvironmentConfiguration(false));
        WorkflowRun run = j.buildAndAssertSuccess(job("123", "echo 'ok'"));
        AmambaConfiguration.get().setWorkspaceEnvironment(new WorkspaceEnvironmentConfiguration(true));
        var contributor = new WorkspaceEnvironmentContributor();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 20; i++)
                tasks.add(() -> {
                    EnvVars env = new EnvVars();
                    contributor.buildEnvironmentFor(run, env, TaskListener.NULL);
                    return env.get(WorkspaceEnvironmentContributor.VARIABLE);
                });
            for (var result : pool.invokeAll(tasks)) assertEquals("研发 Team A", result.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, requests.get());
    }

    @Test
    public void declarativeAndExplicitOverride() throws Exception {
        WorkflowJob job = job(
                "123",
                "pipeline { agent any; stages { stage('check') { steps { echo env.DCE5_WORKSPACE_NAME; withEnv(['DCE5_WORKSPACE_NAME=explicit']) { sh 'echo override=$DCE5_WORKSPACE_NAME' } } } } }");
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("研发 Team A", run);
        j.assertLogContains("override=explicit", run);
        assertEquals(1, requests.get());
    }

    @Test
    public void cascConfiguration() throws Exception {
        var yaml = java.nio.file.Files.createTempFile("amamba-casc", ".yaml");
        try {
            java.nio.file.Files.writeString(
                    yaml,
                    "unclassified:\n  daocloudAmamba:\n    platformUrl: 'https://platform.example.com'\n    credentialsId: 'from-casc'\n    requestTimeoutSeconds: 7\n    workspaceEnvironment:\n      enabled: true\n");
            io.jenkins.plugins.casc.ConfigurationAsCode.get().configure(yaml.toString());
            AmambaConfiguration c = AmambaConfiguration.get();
            assertEquals("https://platform.example.com", c.getPlatformUrl());
            assertEquals("from-casc", c.getCredentialsId());
            assertEquals(7, c.getRequestTimeoutSeconds());
            assertTrue(c.getWorkspaceEnvironment().isEnabled());
        } finally {
            java.nio.file.Files.deleteIfExists(yaml);
        }
    }

    @Test
    public void timerReplayAndWorkspaceIsolation() throws Exception {
        WorkflowJob firstJob = job("123", "echo env.DCE5_WORKSPACE_NAME");
        WorkflowRun timerRun = firstJob.scheduleBuild2(
                        0, new hudson.model.CauseAction(new hudson.triggers.TimerTrigger.TimerTriggerCause()))
                .get();
        j.assertBuildStatusSuccess(timerRun);
        j.assertLogContains("研发 Team A", timerRun);
        body = "{\"workspace\":{\"alias\":\"second workspace\"}}";
        WorkflowRun second = j.buildAndAssertSuccess(job("456", "echo env.DCE5_WORKSPACE_NAME"));
        j.assertLogContains("second workspace", second);
        assertEquals(
                "研发 Team A", timerRun.getEnvironment(TaskListener.NULL).get(WorkspaceEnvironmentContributor.VARIABLE));
        body = "{\"workspace\":{\"alias\":\"renamed for replay\"}}";
        var replay = timerRun.getAction(org.jenkinsci.plugins.workflow.cps.replay.ReplayAction.class);
        WorkflowRun replayRun = (WorkflowRun) replay.run(replay.getOriginalScript(), java.util.Collections.emptyMap())
                .get();
        j.assertBuildStatusSuccess(replayRun);
        j.assertLogContains("renamed for replay", replayRun);
        assertEquals(3, requests.get());
    }

    @Test
    public void configurationRoundTrip() throws Exception {
        j.configRoundtrip();
        AmambaConfiguration c = AmambaConfiguration.get();
        assertTrue(c.getWorkspaceEnvironment().isEnabled());
        assertEquals("platform", c.getCredentialsId());
        assertEquals(5, c.getRequestTimeoutSeconds());
    }
}
