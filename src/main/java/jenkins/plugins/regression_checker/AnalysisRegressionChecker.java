package jenkins.plugins.regression_checker;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.analysis.core.AbstractResultAction;
import hudson.plugins.analysis.core.BuildResult;
import hudson.plugins.checkstyle.CheckStyleResultAction;
import hudson.plugins.cobertura.CoberturaBuildAction;
import hudson.plugins.cobertura.targets.CoverageMetric;
import hudson.plugins.cobertura.targets.CoverageResult;
import hudson.plugins.findbugs.FindBugsResultAction;
import hudson.plugins.pmd.PmdResultAction;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;

public class AnalysisRegressionChecker extends Recorder {
	private final boolean checkPMD;
	private final boolean checkFindbugs;
	private final boolean checkCheckstyle;
	private final boolean checkCobertura;
	private final int COVERAGE_THRESHOLD  = 85;

    //TODO Lots of refactoring...
	@DataBoundConstructor
	public AnalysisRegressionChecker(Boolean checkPMD, Boolean checkFindbugs,
			Boolean checkCheckstyle, Boolean checkCobertura) {
		// Boolean and not boolean because younger version of Hudson fails to
		// convert null to false
		// if the plugin is not installed.
		this.checkPMD = checkPMD != null && checkPMD;
		this.checkCheckstyle = checkCheckstyle != null && checkCheckstyle;
		this.checkFindbugs = checkFindbugs != null && checkFindbugs;
		this.checkCobertura = checkCobertura != null && checkCobertura;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		checkStatisticalCodeAnalysisRegression(build, listener);
		checkCoverageRegression(build, listener);
		// TODO Support different tools, Emma, Clover etc
		return true;
	}

	private void checkStatisticalCodeAnalysisRegression(
			AbstractBuild<?, ?> build, BuildListener listener) {
		if (checkPMD)
			check(build, listener, PmdResultAction.class);
		if (checkFindbugs)
			check(build, listener, FindBugsResultAction.class);
		if (checkCheckstyle)
			check(build, listener, CheckStyleResultAction.class);
	}

	private void checkCoverageRegression(AbstractBuild<?, ?> build,
			BuildListener listener) {
		if (checkCobertura) {
			CoberturaBuildAction buildAction = build
					.getAction(CoberturaBuildAction.class);
			if (buildAction != null) {
				CoverageResult buildResult = buildAction.getResult();

				AbstractBuild<?, ?> lastSuccessBuild = getLatestSuccessfulBuild(build);

				if (lastSuccessBuild != null) {
					CoberturaBuildAction lastSuccessBuildAction = lastSuccessBuild
							.getAction(CoberturaBuildAction.class);
					if (lastSuccessBuildAction != null) {
						CoverageResult lastSuccessBuildResult = lastSuccessBuildAction
								.getResult();
						checkCoverageRegressionForCoverageMetric(build,
								listener, buildResult, lastSuccessBuildResult,
								CoverageMetric.CONDITIONAL,
								lastSuccessBuild.getNumber());
						checkCoverageRegressionForCoverageMetric(build,
								listener, buildResult, lastSuccessBuildResult,
								CoverageMetric.LINE,
								lastSuccessBuild.getNumber());

					}
				}
			}
		}
	}

	private AbstractBuild<?, ?> getLatestSuccessfulBuild(
			AbstractBuild<?, ?> build) {
		AbstractBuild<?, ?> previousBuild = build.getPreviousBuild();
		while (previousBuild != null
				&& previousBuild.getResult() != Result.SUCCESS)
			previousBuild = previousBuild.getPreviousBuild();
		return previousBuild;
	}

	private void checkCoverageRegressionForCoverageMetric(
			AbstractBuild<?, ?> build, BuildListener listener,
			CoverageResult buildResult, CoverageResult lastSuccessBuildResult,
			CoverageMetric coverageMetric, int buildNumber) {

		float diff = lastSuccessBuildResult.getCoverage(coverageMetric)
				.getPercentageFloat()
				- buildResult.getCoverage(coverageMetric).getPercentageFloat();

		if (diff > 0) {
			listener.getLogger().println(
					coverageRegressionFailureMessageForMetric(buildNumber,
							diff, coverageMetric));
			if (buildResult.getCoverage(coverageMetric).getPercentageFloat() > COVERAGE_THRESHOLD) {
				listener.getLogger().println("Lower coverage than buildnumber: " + buildNumber +", but above threshold: " + COVERAGE_THRESHOLD + " so not failing build!");
			} else {
				setBuildStatus(build);
			}
		}
	}

	private void setBuildStatus(AbstractBuild<?, ?> build) {
		build.setResult(Result.FAILURE);
	}

	private <T extends AbstractResultAction<R>, R extends BuildResult> void check(
			AbstractBuild<?, ?> build, BuildListener listener,
			Class<T> resultType) {
		T buildAction = build.getAction(resultType);
		if (buildAction != null) {
			// find the previous successful build
			AbstractBuild<?, ?> lastSuccessfulBuild = getLatestSuccessfulBuild(build);

			if (lastSuccessfulBuild != null) {
				T lastSuccessBuildAction = lastSuccessfulBuild
						.getAction(resultType);
				if (lastSuccessBuildAction != null) {
					R lastSuccessBuildResult = lastSuccessBuildAction
							.getResult();
					R buildResult = buildAction.getResult();
					int diff = buildResult.getNumberOfWarnings()
							- lastSuccessBuildResult.getNumberOfWarnings();
					if (diff > 0) {
						listener.getLogger()
								.println(
										Messages.PmdRegressionChecker_RegressionsDetected2(
												buildAction.getDisplayName(),
												lastSuccessfulBuild.getNumber(),
												diff));
						setBuildStatus(build);
						return;
					}
				}
			}
		}
	}

	public boolean isCheckPMD() {
		return checkPMD;
	}

	public boolean isCheckFindbugs() {
		return checkFindbugs;
	}

	public boolean isCheckCheckstyle() {
		return checkCheckstyle;
	}

	public boolean isCheckCobertura() {
		return checkCobertura;
	}

	private String coverageRegressionFailureMessageForMetric(int buildNr,
			float diff, CoverageMetric coverageMetric) {
		String metricsText = "";
		switch (coverageMetric) {
		case CONDITIONAL:
			metricsText = "branchcoverage ";
			break;
		case LINE:
			metricsText = "linecoverage ";
			break;
		default:
			metricsText = "coverage";
		}
		NumberFormat percentInstance = DecimalFormat.getPercentInstance();
		percentInstance.setMinimumFractionDigits(1);
		percentInstance.setMaximumFractionDigits(1);

		return "Regressions detected in Coverage Report. Compared to the current base line at build #"
				+ buildNr
				+ ", still "
				+ percentInstance.format(diff / 100)
				+ " increased " + metricsText + "needed";
	}

	@Extension(ordinal = -100)
	public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		public boolean hasFindbugs() {
			try {
				FindBugsResultAction.class.getName();
				return true;
			} catch (LinkageError e) {
				return false;
			}
		}

		public boolean hasPMD() {
			try {
				PmdResultAction.class.getName();
				return true;
			} catch (LinkageError e) {
				return false;
			}
		}

		public boolean hasCheckstyle() {
			try {
				CheckStyleResultAction.class.getName();
				return true;
			} catch (LinkageError e) {
				return false;
			}
		}

		public boolean hasCobertura() {
			try {
				CoberturaBuildAction.class.getName();
				return true;
			} catch (LinkageError e) {
				return false;
			}
		}

		@Override
		public String getDisplayName() {
			return "Fail the build if the code analysis worsens";
		}
	}
}
