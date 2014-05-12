package org.jvnet.hudson.plugins;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.EnvironmentSpecific;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.slaves.NodeSpecific;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.DownloadFromUrlInstaller;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sbt plugin {@link Builder}.
 * <p/>
 * <p/>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link SbtPluginBuilder} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 * <p/>
 * <p/>
 * When a build is performed, the
 * {@link #perform(AbstractBuild, Launcher, BuildListener)} method will be
 * invoked.
 *
 * @author Uzi Landsmann
 */
public class SbtPluginBuilder extends Builder {

    public static final Logger LOGGER = Logger.getLogger(SbtPluginBuilder.class
        .getName());

    private final String name;
    private final String jvmFlags;
    private final String sbtFlags;
    private final String actions;
    private String subdirPath;

    // Fields in config.jelly must match the parameter names in the
    // "DataBoundConstructor"
    @DataBoundConstructor
    public SbtPluginBuilder(String name, String jvmFlags, String sbtFlags,
                            String actions, String subdirPath) {
        this.name = name;
        this.jvmFlags = jvmFlags;
        this.sbtFlags = sbtFlags;
        this.actions = actions;
        this.subdirPath = subdirPath;
    }

    public String getName() {
        return name;
    }

    public String getJvmFlags() {
        return jvmFlags;
    }

    public String getSbtFlags() {
        return sbtFlags;
    }

    public String getActions() {
        return actions;
    }

    public String getSubdirPath() {
        return subdirPath;
    }

    /**
     * Perform the sbt build. Interpret the command arguments and create a
     * command line, then run it.
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher,
                           BuildListener listener) {

        EnvVars env = null;
        FilePath workDir = build.getModuleRoot();
        try {

            ArgumentListBuilder cmdLine = buildCmdLine(build, launcher,
                listener);
            String[] cmds = cmdLine.toCommandArray();
            env = build.getEnvironment(listener);

            if (subdirPath != null && subdirPath.length() > 0) {
                workDir = new FilePath(workDir, subdirPath);
            }

            int exitValue = launcher.launch().cmds(cmds).envs(env)
                .stdout(listener).pwd(workDir).join();

            boolean success = (exitValue == 0);
            build.setResult(success ? Result.SUCCESS : Result.FAILURE);
            return success;
        } catch (IllegalArgumentException e) {
            // Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("command execution failed: "
                + e.getMessage()));
            build.setResult(Result.FAILURE);
            return false;
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("command execution failed: "
                + e.getMessage()));
            build.setResult(Result.FAILURE);
            return false;
        } catch (InterruptedException e) {
            // Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("command execution failed: "
                + e.getMessage()));
            build.setResult(Result.ABORTED);
            return false;
        }

    }

    /**
     * Create an {@link ArgumentListBuilder} to run the build, given command
     * arguments.
     */
    private ArgumentListBuilder buildCmdLine(AbstractBuild build,
                                             Launcher launcher, BuildListener listener)
        throws IllegalArgumentException, InterruptedException, IOException {
        ArgumentListBuilder args = new ArgumentListBuilder();

//		DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();

        EnvVars env = build.getEnvironment(listener);
        env.overrideAll(build.getBuildVariables());

        SbtInstallation sbt = getSbt();
        if (sbt == null) {
            throw new IllegalArgumentException("sbt-launch.jar not found");
        } else {
            sbt = sbt.forNode(Computer.currentComputer().getNode(), listener);
            sbt = sbt.forEnvironment(env);

            String launcherPath = sbt.getSbtLaunchJar(launcher);

            if (launcherPath == null) {
                throw new IllegalArgumentException("sbt-launch.jar not found");
            }

            if (!launcher.isUnix()) {
                args.add("cmd.exe", "/C");
            }

            // java
            String javaExePath;

            JDK jdk = build.getProject().getJDK();
            Computer computer = Computer.currentComputer();
            if (computer != null && jdk != null) { // just in case were not in a build
                // use node specific installers, etc
                jdk = jdk.forNode(computer.getNode(), listener);
            }

            if (jdk != null) {
                javaExePath = jdk.getHome() + "/bin/java";
            } else {
                javaExePath = "java";
            }
            args.add(javaExePath);

            splitAndAddArgs(env.expand(jvmFlags), args);
            splitAndAddArgs(env.expand(sbtFlags), args);

            // additionnal args from .sbtopts file
            FilePath sbtopts = build.getProject().getWorkspace().child(".sbtopts");
            if (sbtopts.exists()) {
                String argsToSplit = sbtopts.readToString();
                if (!StringUtils.isBlank(argsToSplit)) {
                    String[] split = argsToSplit.split("\\s+");
                    for (String flag : split) {
                        if (flag.startsWith("-J")) {
                          args.add(flag.substring(2));
                        } else {
                          args.add(flag);
                        }
                    }
                }
            }

            args.add("-jar");

            args.add(launcherPath);

            String subActions = new StrSubstitutor(env).replace(actions);
            for (String action : split(subActions)) {
                args.add(action);
            }
        }

        return args;
    }

    private SbtInstallation getSbt() {
        for (SbtInstallation sbtInstallation : getDescriptor().getInstallations()) {
            if (name != null && name.equals(sbtInstallation.getName())) {
                return sbtInstallation;
            }
        }
        return null;
    }

    /**
     * Split arguments and add them to the args list
     *
     * @param argsToSplit the arguments to split
     * @param args        java/sbt command arguments
     */
    private void splitAndAddArgs(String argsToSplit, ArgumentListBuilder args) {
        if (StringUtils.isBlank(argsToSplit)) {
            return;
        }

        String[] split = argsToSplit.split("\\s+");
        for (String flag : split) {
            args.add(flag);
        }
    }

    /*
     * Splits by whitespace except if surrounded by quotes. See
     * http://stackoverflow
     * .com/questions/366202/regex-for-splitting-a-string-using
     * -space-when-not-surrounded-by-single-or-double/366532#366532
     */
    private List<String> split(String s) {
        List<String> result = new ArrayList<String>();
        Matcher matcher = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")
            .matcher(s);
        while (matcher.find()) {
            if (matcher.group(1) != null)
                result.add(matcher.group(1));
            else if (matcher.group(2) != null)
                result.add(matcher.group(2));
            else
                result.add(matcher.group());
        }
        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link SbtPluginBuilder}. Used as a singleton. The class
     * is marked as public so that it can be accessed from views.
     * <p/>
     * <p/>
     * See <tt>SbtPluginBuilder/*.jelly</tt> for the actual HTML fragment for
     * the configuration screen.
     */
    @Extension
    // this marker indicates Hudson that this is an implementation of an
    // extension point.
    public static final class DescriptorImpl extends
        BuildStepDescriptor<Builder> {

//		private volatile Jar[] jars = new Jar[0];

        @CopyOnWrite
        private volatile SbtInstallation[] installations = new SbtInstallation[0];

        public DescriptorImpl() {
            super(SbtPluginBuilder.class);
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return "Build using sbt";
        }

		/*public Jar getJar(String name) {
            for (Jar jar : jars) {
				if (jar.getName().equals(name)) {
					return jar;
				}
			}
			return null;
		}*/

		/*@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			
			try {
				jars = req.bindJSONToList(Jar.class,
						req.getSubmittedForm().get("jar")).toArray(new Jar[0]);
				save();
				return true;
			} catch (ServletException e) {

				LOGGER.severe(String.format("Couldn't save jars beacause %s",
						e.getMessage()));
				LOGGER.severe(String.format("Stacktrace %s", e.getStackTrace()
						.toString()));

				return false;
			}
		}*/

		/*public Jar[] getJars() {
			return jars;
		}*/

        public SbtInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(SbtInstallation.DescriptorImpl.class);
        }

        public SbtInstallation[] getInstallations() {
            return installations;
        }

        public void setInstallations(SbtInstallation... sbtInstallations) {
            this.installations = sbtInstallations;
            save();
        }

    }

    /**
     * Representation of an sbt launcher. Several such launchers can be defined
     * in Jenkins properties to choose among when running a project.
     */
	/*public static final class Jar implements Serializable {
		private static final long serialVersionUID = 1L;
    */
    /** The human-friendly name of this launcher */
    //	private String name;

    /**
     * The path to the launcher
     */
    //	private String path;
    /*
		@DataBoundConstructor
		public Jar(String name, String path) {
			this.name = name;
			this.path = path;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}
	} */

    public static final class SbtInstallation extends ToolInstallation implements
        EnvironmentSpecific<SbtInstallation>, NodeSpecific<SbtInstallation>, Serializable {

        private static final long serialVersionUID = -2281774135009218882L;

        private String sbtLaunchJar;


        @DataBoundConstructor
        public SbtInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
            super(name, launderHome(home), properties);
        }

        private static String launderHome(String home) {
            if (home.endsWith("/") || home.endsWith("\\")) {
                // see https://issues.apache.org/bugzilla/show_bug.cgi?id=26947
                // Ant doesn't like the trailing slash, especially on Windows
                return home.substring(0, home.length() - 1);
            } else {
                return home;
            }
        }

        public String getSbtLaunchJar(Launcher launcher) throws IOException, InterruptedException {
            return launcher.getChannel().call(new Callable<String, IOException>() {
                public String call() throws IOException {
                    File sbtLaunchJarFile = getSbtLaunchJarFile();
                    if(sbtLaunchJarFile.exists())
                        return sbtLaunchJarFile.getPath();
                    return getHome();
                }
            });
        }

        private File getSbtLaunchJarFile() {
            String home = Util.replaceMacro(getHome(), EnvVars.masterEnvVars);
            return new File(home, "bin/sbt-launch.jar");
        }

        public SbtInstallation forEnvironment(EnvVars environment) {
            return new SbtInstallation(getName(), environment.expand(getHome()), getProperties().toList());
        }

        public SbtInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
            return new SbtInstallation(getName(), translateFor(node, log), getProperties().toList());
        }

        @Extension
        public static class DescriptorImpl extends ToolDescriptor<SbtInstallation> {

            @Override
            public SbtInstallation[] getInstallations() {
                return Jenkins.getInstance().getDescriptorByType(SbtPluginBuilder.DescriptorImpl.class)
                    .getInstallations();
            }

            @Override
            public void setInstallations(SbtInstallation... installations) {
                Jenkins.getInstance().getDescriptorByType(SbtPluginBuilder.DescriptorImpl.class)
                    .setInstallations(installations);
            }

            @Override
            public List<? extends ToolInstaller> getDefaultInstallers() {
                return Collections.singletonList(new SbtInstaller(null));
            }

            @Override
            public String getDisplayName() {
                return "Sbt";
            }

            /**
             * Checks if the sbt-launch.jar is exist.
             */
            public FormValidation doCheckHome(@QueryParameter File value) {

                if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                    return FormValidation.ok();
                }

                // allow empty input
                if(value.getPath().equals(""))
                    return FormValidation.ok();

                if (!value.exists() || !value.isFile()) {
                    return FormValidation.error("sbt-launch.jar not found");
                }

                return FormValidation.ok();
            }
        }
    }

    /**
     * Automatic Sbt installer from scala-sbt.org
     */
    public static class SbtInstaller extends DownloadFromUrlInstaller {

        @DataBoundConstructor
        public SbtInstaller(String id) {
            super(id);
        }

        @Extension
        public static final class DescriptorImpl extends DownloadFromUrlInstaller.DescriptorImpl<SbtInstaller> {

            @Override
            public String getDisplayName() {
                return "Install from scala-sbt.org";
            }

            @Override
            public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
                return toolType == SbtInstallation.class;
            }
        }
    }
}
