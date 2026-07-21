package io.github.mmhelloworld.idris.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    var entryPoint = mainClass != null && !mainClass.isBlank()
        ? mainClass
        : mainClassFor(resolvePackageFile());
    getLog().info("Running Idris program " + entryPoint);
    exec(runCommand(entryPoint), project.getBasedir(), configuredEnvironment(), "idris-run");
  }

  private List<String> runCommand(String entryPoint) throws MojoExecutionException {
    var command = new ArrayList<String>();
    command.add(javaExecutable());
    command.addAll(splitArgs(runJvmArgs));
    command.addAll(List.of("-cp", String.join(File.pathSeparator, runtimeClasspath()), entryPoint));
    command.addAll(programArguments());
    return command;
  }

  private List<String> runtimeClasspath() throws MojoExecutionException {
    try {
      return project.getRuntimeClasspathElements();
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Failed to resolve runtime classpath", e);
    }
  }

  private List<String> programArguments() {
    return args != null ? args : List.of();
  }
}
