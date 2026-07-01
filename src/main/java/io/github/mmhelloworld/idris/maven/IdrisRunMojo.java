package io.github.mmhelloworld.idris.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the compiled Idris program on the JVM, using the project's runtime classpath (compiled
 * output plus the Idris JVM runtime and other dependencies).
 */
@Mojo(name = "run",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true)
public class IdrisRunMojo extends AbstractIdrisMojo {

    /**
     * Fully qualified main class of the compiled program. When omitted it is derived from the
     * package's {@code executable} field as {@code <executable>.JvmMain}.
     */
    @Parameter(property = "idris.mainClass")
    private String mainClass;

    /** Arguments passed to the program. */
    @Parameter(property = "idris.args")
    private List<String> args;

    /** JVM options for the run (separate from the compiler's options). */
    @Parameter(property = "idris.run.jvmArgs", defaultValue = "")
    private String runJvmArgs;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping Idris run (idris.skip=true)");
            return;
        }

        String entryPoint = mainClass;
        if (entryPoint == null || entryPoint.isBlank()) {
            entryPoint = mainClassFor(resolvePackageFile());
        }

        List<String> classpath;
        try {
            classpath = project.getRuntimeClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve runtime classpath", e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExec());
        if (runJvmArgs != null && !runJvmArgs.isBlank()) {
            for (String arg : runJvmArgs.trim().split("\\s+")) {
                command.add(arg);
            }
        }
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(entryPoint);
        if (args != null) {
            command.addAll(args);
        }

        getLog().info("Running Idris program " + entryPoint);
        Map<String, String> env = environmentVariables != null ? environmentVariables : Map.of();
        exec(command, project.getBasedir(), env, "idris-run");
    }
}
