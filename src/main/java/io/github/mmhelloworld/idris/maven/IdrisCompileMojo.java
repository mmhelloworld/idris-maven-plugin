package io.github.mmhelloworld.idris.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.List;

/**
 * Compiles Idris sources to JVM bytecode by running {@code idris2 --build <package>.ipkg} with the
 * JVM backend, then copies the emitted classes into the project's output directory so they are
 * packaged into the artifact like any other compiled class.
 */
@Mojo(name = "compile",
    defaultPhase = LifecyclePhase.COMPILE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public class IdrisCompileMojo extends AbstractIdrisMojo {

  /** Directory into which compiled Idris classes are copied. */
  @Parameter(defaultValue = "${project.build.outputDirectory}", property = "idris.outputDirectory")
  private File outputDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping Idris compilation (idris.skip=true)");
      return;
    }
    var pkg = resolvePackageFile();
    var workingDirectory = pkg.getParentFile();
    getLog().info("Compiling Idris package " + pkg.getName() + " (JVM backend)");
    runIdris(List.of("--build", pkg.getAbsolutePath()), workingDirectory);
    var copied = copyCompiledClasses(workingDirectory, outputDirectory);
    getLog().info("Copied " + copied + " compiled class file(s) to " + outputDirectory);
  }
}
