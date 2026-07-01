package io.github.mmhelloworld.idris.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs an Idris test package on the JVM. The test package is expected to produce an
 * executable that exits with a non-zero status when a test fails, failing the build accordingly.
 */
@Mojo(name = "test",
    defaultPhase = LifecyclePhase.TEST,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class IdrisTestMojo extends AbstractIdrisMojo {

    /** The Idris test package file ({@code .ipkg}) to build and run. */
    @Parameter(property = "idris.testPackageFile")
    private File testPackageFile;

    /**
     * Main class of the compiled test program. When omitted it is derived from the test package's
     * {@code executable} field as {@code <executable>.JvmMain}.
     */
    @Parameter(property = "idris.testMainClass")
    private String testMainClass;

    @Parameter(property = "skipTests", defaultValue = "false")
    private boolean skipTests;

    @Parameter(property = "maven.test.skip", defaultValue = "false")
    private boolean mavenTestSkip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip || skipTests || mavenTestSkip) {
            getLog().info("Skipping Idris tests");
            return;
        }
        if (testPackageFile == null) {
            getLog().info("No <testPackageFile> configured; skipping Idris tests");
            return;
        }
        if (!testPackageFile.isFile()) {
            throw new MojoExecutionException("Idris test package file not found: " + testPackageFile);
        }

        File workingDirectory = testPackageFile.getParentFile();
        String entryPoint = testMainClass;
        if (entryPoint == null || entryPoint.isBlank()) {
            entryPoint = mainClassFor(testPackageFile);
        }

        getLog().info("Building Idris test package " + testPackageFile.getName());
        runIdris(List.of("--build", testPackageFile.getAbsolutePath()), workingDirectory);

        List<String> classpath;
        try {
            classpath = project.getTestClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve test classpath", e);
        }
        // Include the freshly-built test classes emitted next to the test package.
        File testExec = new File(workingDirectory, "build/exec");
        if (testExec.isDirectory()) {
            File[] appDirs = testExec.listFiles((d, name) -> name.endsWith("_app"));
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    classpath.add(appDir.getAbsolutePath());
                }
            }
        }

        List<String> command = new ArrayList<>();
        command.add(javaExec());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(entryPoint);

        getLog().info("Running Idris tests " + entryPoint);
        Map<String, String> env = environmentVariables != null ? environmentVariables : Map.of();
        exec(command, workingDirectory, env, "idris-test");
    }
}
