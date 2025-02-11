package org.jvnet.hudson.plugins;

import static org.junit.Assume.assumeFalse;

import java.util.List;

import hudson.Functions;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.DownloadService;
import hudson.model.Result;
import hudson.tools.InstallSourceProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SbtPluginBuilderTest {

    @Test
    public void testSbtInstallation(JenkinsRule jenkinsRule) throws Exception {
        final var installationDescriptor = jenkinsRule.get(SbtPluginBuilder.SbtInstallation.DescriptorImpl.class);
        Assertions.assertEquals(0, installationDescriptor.getInstallations().length);

        final var installer = new SbtPluginBuilder.SbtInstaller("installer1");
        final var installSourceProperty = new InstallSourceProperty(List.of(installer));

        final var descriptor = jenkinsRule.jenkins.getDescriptorByType(SbtPluginBuilder.DescriptorImpl.class);
        final var sbtInstallation = new SbtPluginBuilder.SbtInstallation("sbt1", null, null, List.of(installSourceProperty));
        descriptor.setInstallations(sbtInstallation);

        Assertions.assertEquals(1, installationDescriptor.getInstallations().length);
    }

    @Test
    public void testFreestyle(JenkinsRule jenkinsRule) throws Exception {
        DownloadService.Downloadable mvnDl =
                DownloadService.Downloadable.get("org.jvnet.hudson.plugins.SbtPluginBuilder.SbtInstaller");

        mvnDl.updateNow();

        final var installer = new SbtPluginBuilder.SbtInstaller("1.10.5");
        final var installSourceProperty = new InstallSourceProperty(List.of(installer));

        final var descriptor = jenkinsRule.jenkins.getDescriptorByType(SbtPluginBuilder.DescriptorImpl.class);
        final var sbtInstallation = new SbtPluginBuilder.SbtInstallation("sbt1", null, null, List.of(installSourceProperty));
        descriptor.setInstallations(sbtInstallation);

        final var freestyle1 = jenkinsRule.createFreeStyleProject("freestyle1");
        final var sbtPluginBuilder = new SbtPluginBuilder("sbt1", null, null, "about", null);
        freestyle1.getBuildersList().add(sbtPluginBuilder);

        final var freeStyleBuildQueueTaskFuture = freestyle1.scheduleBuild2(0, new Cause.UserIdCause());

        final var freeStyleBuild = freeStyleBuildQueueTaskFuture.get();

        jenkinsRule.assertBuildStatus(Result.SUCCESS, freeStyleBuild);
        jenkinsRule.assertLogContains("1.10.5", freeStyleBuild);
    }

    @Test
    public void testPipeline(JenkinsRule jenkinsRule) throws Exception {
        assumeFalse(Functions.isWindows());

        DownloadService.Downloadable mvnDl =
                DownloadService.Downloadable.get("org.jvnet.hudson.plugins.SbtPluginBuilder.SbtInstaller");

        mvnDl.updateNow();

        final var installer = new SbtPluginBuilder.SbtInstaller("1.10.5");
        final var installSourceProperty = new InstallSourceProperty(List.of(installer));

        final var descriptor = jenkinsRule.jenkins.getDescriptorByType(SbtPluginBuilder.DescriptorImpl.class);
        final var sbtInstallation = new SbtPluginBuilder.SbtInstallation("sbt1", null, null, List.of(installSourceProperty));
        descriptor.setInstallations(sbtInstallation);

        final var project = jenkinsRule.createProject(WorkflowJob.class, "project1");
        project.setDefinition(new CpsFlowDefinition("pipeline { agent any \n tools { sbt 'sbt1' } \n stages { stage('build') { steps { sh 'sbt about' } } } }", true));

        final var workflowRunQueueTaskFuture = project.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause()));

        final var workflowRun = workflowRunQueueTaskFuture.get();

        jenkinsRule.assertBuildStatus(Result.SUCCESS, workflowRun);
        jenkinsRule.assertLogContains("1.10.5", workflowRun);
    }
}
