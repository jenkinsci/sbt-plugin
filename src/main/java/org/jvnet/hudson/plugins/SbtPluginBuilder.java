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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Sample {@link Builder}.
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
 * @author Kohsuke Kawaguchi
 */
public class SbtPluginBuilder extends Builder {

	private final String actions;
	private final String jvmFlags;
	private final String sbtFlags;

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public SbtPluginBuilder(String jvmFlags, String sbtFlags, String actions) {
		this.jvmFlags = jvmFlags;
		this.sbtFlags = sbtFlags;
		this.actions = actions;
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
			e.printStackTrace(listener.fatalError("command execution failed"));
			build.setResult(Result.FAILURE);
			return false;
		} catch (IOException e) {
			Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("command execution failed"));
			build.setResult(Result.FAILURE);
			return false;
		} catch (InterruptedException e) {
			// Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("command execution failed"));
			build.setResult(Result.FAILURE);
			return false;
		}

	}

	private ArgumentListBuilder buildCmdLine(AbstractBuild build,
			Launcher launcher, BuildListener listener)
			throws IllegalArgumentException {
		ArgumentListBuilder args = new ArgumentListBuilder();

		String sbtJarPath = getDescriptor().getSbtJarPath();

		if (StringUtils.isBlank(sbtJarPath)) {
			throw new IllegalArgumentException("SBT jar path is empty");
		}

		if (!launcher.isUnix()) {
			args.add("cmd.exe", "/C");
		}

		// java
		String java = build.getProject().getJDK() != null ? build.getProject()
				.getJDK().getBinDir()
				+ "/java" : "java";
		args.add(new File(java).getAbsolutePath());

		String[] split = jvmFlags.split(" ");
		for (String flag : split) {
			args.add(flag);
		}

		split = sbtFlags.split(" ");
		for (String flag : split) {
			args.add(flag);
		}

		args.add("-jar");

		args.add(sbtJarPath);

		for (String action : split(actions)) {
			args.add(action);
		}

		return args;
	}

	/*
	 * Splits by whitespace except if surrounded by quotes.
	 * See http://stackoverflow.com/questions/366202/regex-for-splitting-a-string-using-space-when-not-surrounded-by-single-or-double/366532#366532
	 */
	private List<String> split(String s) {
		List<String> result = new ArrayList<String>();
		Matcher matcher = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'").matcher(s);
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

	// overrided for better type safety.
	// if your plugin doesn't really define any property on Descriptor,
	// you don't have to do this.
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

		private String sbtJarPath;

		public DescriptorImpl() {
			load();
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
		 * 
		 * @param value
		 *            This parameter receives the value that the user has typed.
		 * @return Indicates the outcome of the validation. This is sent to the
		 *         browser.
		 */
		public FormValidation doCheckSbtJarPath(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.isEmpty()) {
				return FormValidation.error("Please enter a path");
			}
			return FormValidation.ok();
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			// indicates that this builder can be used with all kinds of project
			// types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Build using SBT";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			sbtJarPath = formData.getString("sbtJarPath");

			save();
			return super.configure(req, formData);
		}

		public String getSbtJarPath() {
			return sbtJarPath;
		}

	}
}
