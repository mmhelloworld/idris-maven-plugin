package io.github.mmhelloworld.idris.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Packages an Idris library as a Maven artifact. Runs {@code idris2 --install <package>.ipkg}
 * (which builds first) with {@code IDRIS2_PREFIX} pointed at a project-local staging directory,
 * zips the installed package directory (ipkg metadata plus the checked {@code .ttc} tree), and
 * attaches the zip to the project with classifier {@code idris}. Maven's standard install/deploy
 * phases then publish it like any other artifact, and dependent projects consume it with
 * {@code <classifier>idris</classifier><type>zip</type>} plus a {@code depends} entry in their
 * own {@code .ipkg}.
 */
@Mojo(name = "package",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public class IdrisPackageMojo extends AbstractIdrisMojo {

  @Component
  private MavenProjectHelper projectHelper;

  /** Staging prefix into which the Idris package is installed before zipping. */
  @Parameter(property = "idris.stagingDirectory",
      defaultValue = "${project.build.directory}/idris/prefix")
  private File stagingDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping Idris packaging (idris.skip=true)");
      return;
    }
    var pkg = resolvePackageFile();
    getLog().info("Packaging Idris library " + pkg.getName() + " (JVM backend)");
    var staging = prepareStagingDirectory();
    runIdris(List.of("--install", pkg.getAbsolutePath()), pkg.getParentFile(), staging);
    var zip = zipInstalledPackages(findInstalledPackagesDir(staging));
    projectHelper.attachArtifact(project, IDRIS_PACKAGE_TYPE, IDRIS_CLASSIFIER, zip.toFile());
    getLog().info("Attached Idris package artifact " + project.getGroupId() + ":"
        + project.getArtifactId() + ":" + IDRIS_PACKAGE_TYPE + ":" + IDRIS_CLASSIFIER + ":"
        + project.getVersion() + " (" + zip + ")");
  }

  /** Empties and recreates the staging prefix so stale packages never leak into the zip. */
  private Path prepareStagingDirectory() throws MojoExecutionException {
    var staging = stagingDirectory.toPath();
    try {
      deleteRecursively(staging);
      Files.createDirectories(staging);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to prepare staging directory " + staging, e);
    }
    return staging;
  }

  private Path zipInstalledPackages(Path installedPackages) throws MojoExecutionException {
    var zip = Paths.get(project.getBuild().getDirectory(),
        project.getBuild().getFinalName() + "-" + IDRIS_CLASSIFIER + "." + IDRIS_PACKAGE_TYPE);
    try {
      zipDirectory(installedPackages, zip);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to create Idris package zip " + zip, e);
    }
    return zip;
  }

  /**
   * The {@code idris2-<apiVersion>} directory that {@code --install} created under the staging
   * prefix. Its contents (the {@code <pkgname>-<pkgversion>} package dirs) form the zip root, so
   * an extracted zip is directly usable as an {@code IDRIS2_PACKAGE_PATH} entry.
   */
  private Path findInstalledPackagesDir(Path staging) throws MojoExecutionException {
    try {
      return children(staging).stream()
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith("idris2-"))
          .findFirst()
          .orElseThrow(() -> new MojoExecutionException(
              "idris2 --install produced no package under " + staging
                  + ". Does the .ipkg declare its modules in a 'modules' field?"));
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to inspect staging directory " + staging, e);
    }
  }
}
