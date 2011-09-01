package jenkins.plugins.regression_checker;

import java.io.IOException;

import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.plugins.cobertura.CoberturaPublisher;

import hudson.plugins.cobertura.renderers.SourceEncoding;
import org.jvnet.hudson.test.HudsonTestCase;

public class AnalysisRegressionCheckerTest extends HudsonTestCase {
    private static final Result BUILD_FAILURE_STATUS = Result.FAILURE;

  public void testBuildFailsWhenCoverageDecreases() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        assertBuildStatusSuccess(p.scheduleBuild2(0).get()); // baseline
        FilePath coberturaXml = p.getLastBuild().getWorkspace().child("coverage.xml");

        p.getPublishersList().add(new CoberturaPublisher("coverage.xml", true, SourceEncoding.getEncoding("UTF-8")));

        coberturaXml.copyFrom(getClass().getResource("coverage_base.xml"));
        assertBuildStatusSuccess(p.scheduleBuild2(0).get());

        p.getPublishersList().add(new AnalysisRegressionChecker(true,false, true,true));
        
        coberturaXml.copyFrom(getClass().getResource("coverage_decreased.xml"));
        assertBuildStatus(BUILD_FAILURE_STATUS, p.scheduleBuild2(0).get());
    }
    
}
