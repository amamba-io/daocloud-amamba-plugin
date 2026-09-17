package io.jenkins.plugins.daocloud.amamba;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Guards the out-of-the-box configuration for controllers inside the DCE5 cluster. Must not
 * touch any setter: saving persists to JENKINS_HOME, and the assertion observes the pristine
 * default loaded at Jenkins startup.
 */
public class DefaultConfigurationTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void platformUrlDefaultsToInClusterGateway() {
        assertEquals(
                "http://istio-ingressgateway.istio-system.svc.cluster.local",
                AmambaConfiguration.get().getPlatformUrl());
    }
}
