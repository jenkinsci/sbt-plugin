package org.jvnet.hudson.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import jline.ArgumentCompletor;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * sbt plugin {@link Builder}.
 * 
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link SbtPluginBuilder} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 * 
 * <p>
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

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public SbtPluginBuilder(String name, String jvmFlags, String sbtFlags,
			String actions) {
		this.name = name;
		this.jvmFlags = jvmFlags;
		this.sbtFlags = sbtFlags;
		this.actions = actions;
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
			build.setResult(Result.FAILURE);
			return false;
		}

	}

	/**
	 * Create an {@link ArgumentListBuilder} to run the build, given command
	 * arguments.
	 */
	private ArgumentListBuilder buildCmdLine(AbstractBuild build,
			Launcher launcher, BuildListener listener)
			throws IllegalArgumentException {
		ArgumentListBuilder args = new ArgumentListBuilder();

		DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();

		String launcherPath = descriptor.getJar(name).getPath();

		if (StringUtils.isBlank(launcherPath)) {
			throw new IllegalArgumentException("SBT jar path is empty");
		}

		if (!launcher.isUnix()) {
			args.add("cmd.exe", "/C");
		}

		// java
		String javaExePath;
		if (build.getProject().getJDK() != null) {
			javaExePath = new File(build.getProject().getJDK().getBinDir()
					+ "/java").getAbsolutePath();
		} else {
			javaExePath = "java";
		}
		args.add(javaExePath);

		splitAndAddArgs(jvmFlags, args);
		splitAndAddArgs(sbtFlags, args);

		args.add("-jar");

		args.add(launcherPath);

		for (String action : split(actions)) {
			args.add(action);
		}

		return args;
	}

	/**
	 * Split arguments and add them to the args list
	 * 
	 * @param argsToSplit
	 *            the arguments to split
	 * @param args
	 *            java/sbt command arguments
	 */
	private void splitAndAddArgs(String argsToSplit, ArgumentListBuilder args) {
		if (StringUtils.isBlank(argsToSplit)) {
			return;
		}

		String[] split = argsToSplit.split(" ");
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
	 * 
	 * <p>
	 * See <tt>SbtPluginBuilder/*.jelly</tt> for the actual HTML fragment for
	 * the configuration screen.
	 */
	@Extension
	// this marker indicates Hudson that this is an implementation of an
	// extension point.
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Builder> {

		private volatile Jar[] jars = new Jar[0];

		public DescriptorImpl() {
			super(SbtPluginBuilder.class);
			load();
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		@Override
		public Builder newInstance(StaplerRequest req, JSONObject formData) {

			LOGGER.info(String.format("Creating new instance with formData %s",
					formData));

			String name = formData.getString("name");
			String jvmFlags = formData.getString("jvmFlags");
			String sbtFlags = formData.getString("sbtFlags");
			String actions = formData.getString("actions");

			return new SbtPluginBuilder(name, jvmFlags, sbtFlags, actions);
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Build using sbt";
		}

		public Jar getJar(String name) {
			for (Jar jar : jars) {
				if (jar.getName().equals(name)) {
					return jar;
				}
			}
			return null;
		}

		@Override
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
		}

		public Jar[] getJars() {
			return jars;
		}

	}

	/**
	 * Representation of an sbt launcher. Several such launchers can be defined
	 * in Jenkins properties to choose among when running a project.
	 */
	public static final class Jar implements Serializable {
		private static final long serialVersionUID = 1L;

		/** The human-friendly name of this launcher */
		private String name;
		
		/** The path to the launcher */
		private String path;

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
	}
}
