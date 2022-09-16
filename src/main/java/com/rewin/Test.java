package com.rewin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import rewin.ubsi.cli.Request;

import java.util.Set;

/**
 * Mojo测试
 */
@Mojo(
        name="test", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class Test extends AbstractUbsiMojo {

    public void execute() throws MojoExecutionException {
        getLog().info("services: >>>");
        Request.printJson(services);
        getLog().info("filters: >>>");
        Request.printJson(filters);

        String container = System.getProperty("container");
        getLog().info("container: " + container);

        Set<Artifact> artifacts = project.getArtifacts();
        int count = 0;
        for ( Artifact artifact : artifacts ) {
            count ++;
            getLog().info("" + count + ": " + artifact.getDependencyConflictId());
        }
    }

}
