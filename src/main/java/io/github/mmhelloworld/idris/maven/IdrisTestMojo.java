package io.github.mmhelloworld.idris.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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

  /** Skips running Idris tests when {@code true} (mirrors Surefire's {@code skipTests}). */
  @Parameter(property = "skipTests", defaultValue = "false")
  private boolean skipTests;

  /** Skips running Idris tests when {@code true} (mirrors {@code maven.test.skip}). */
  @Parameter(property = "maven.test.skip", defaultValue = "false")
  private boolean mavenTestSkip;

  @Override
  public void execute() throws MojoExecutionException {
    var skipReason = skipReason();
    if (skipReason.isPresent()) {
      getLog().info(skipReason.get());
      return;
    }
    runTests();
  }

  private Optional<String> skipReason() {
    return skip || skipTests || mavenTestSkip ? Optional.of("Skipping Idris tests")
        : testPackageFile == null ? Optional.of("No <testPackageFile> configured; skipping Idris tests")
        : Optional.empty();
  }

  private void runTests() throws MojoExecutionException {
    if (!testPackageFile.isFile()) {
      throw new MojoExecutionException("Idris test package file not found: " + testPackageFile);
    }
    var workingDirectory = testPackageFile.getParentFile();
    getLog().info("Building Idris test package " + testPackageFile.getName());
    runIdris(List.of("--build", testPackageFile.getAbsolutePath()), workingDirectory);
    var entryPoint = testMainClass != null && !testMainClass.isBlank()
        ? testMainClass
        : mainClassFor(testPackageFile);
    getLog().info("Running Idris tests " + entryPoint);
    exec(testCommand(testClasspath(workingDirectory), entryPoint),
        workingDirectory, configuredEnvironment(), "idris-test");
  }

  private List<String> testClasspath(File workingDirectory) throws MojoExecutionException {
    try {
      return Stream.concat(
              project.getTestClasspathElements().stream(),
              builtTestAppClassDirs(workingDirectory).stream())
          .toList();
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Failed to resolve test classpath", e);
    }
  }

  /** The freshly-built test classes emitted next to the test package. */
  private static List<String> builtTestAppClassDirs(File workingDirectory) {
    var execDir = workingDirectory.toPath().resolve("build").resolve("exec");
    return childrenOrEmpty(execDir).stream()
        .filter(Files::isDirectory)
        .filter(dir -> dir.getFileName().toString().endsWith("_app"))
        .map(dir -> dir.toAbsolutePath().toString())
        .toList();
  }

  private static List<String> testCommand(List<String> classpath, String entryPoint) {
    return List.of(javaExecutable(), "-cp", String.join(File.pathSeparator, classpath), entryPoint);
  }
}
