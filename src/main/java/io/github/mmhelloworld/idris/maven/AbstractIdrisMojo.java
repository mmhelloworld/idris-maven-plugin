package io.github.mmhelloworld.idris.maven;

import io.vavr.CheckedConsumer;
import io.vavr.CheckedFunction1;
import io.vavr.control.Option;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Common configuration and helpers shared by the Idris goals: locating the compiler
 * (resolved from Maven or an external executable) and forking it as a child process.
 */
public abstract class AbstractIdrisMojo extends AbstractMojo {

  /** Main class of the Idris JVM compiler inside the distribution's {@code exec/idris2_app}. */
  private static final String COMPILER_MAIN_CLASS = "idris2.JvmMain";

  /** Classifier of Maven artifacts carrying an installed Idris package (ttc tree + ipkg). */
  protected static final String IDRIS_CLASSIFIER = "idris";

  /** Artifact type of Idris package artifacts. */
  protected static final String IDRIS_PACKAGE_TYPE = "zip";

  /** Marker file inside an extracted package dir recording which zip it came from. */
  private static final String SOURCE_MARKER = ".source";

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  protected MavenSession session;

  @Component
  protected RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  protected RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
  protected List<RemoteRepository> remoteRepositories;

  /**
   * Version of the Idris JVM compiler distribution to use. When omitted it is derived from the
   * project's declared {@code idris-jvm-runtime} dependency, so the compiler always matches the
   * runtime you link against (as the Scala plugin does with {@code scala-library}).
   */
  @Parameter(property = "idris.compilerVersion")
  protected String compilerVersion;

  @Parameter(property = "idris.compiler.groupId", defaultValue = "io.github.mmhelloworld")
  protected String compilerGroupId;

  @Parameter(property = "idris.compiler.artifactId", defaultValue = "idris-jvm-compiler")
  protected String compilerArtifactId;

  @Parameter(property = "idris.runtime.artifactId", defaultValue = "idris-jvm-runtime")
  protected String runtimeArtifactId;

  /**
   * Path to an already-installed {@code idris2} executable. When set, the plugin runs it directly
   * instead of resolving the compiler from Maven — an escape hatch for local development.
   */
  @Parameter(property = "idris.executable")
  protected String idrisExecutable;

  /** Directory where resolved compiler distributions are unpacked and cached. */
  @Parameter(property = "idris.compilerHome",
      defaultValue = "${user.home}/.idris-maven/compiler")
  protected File compilerHome;

  /** Directory where Idris library dependency artifacts are extracted and cached. */
  @Parameter(property = "idris.packagesHome",
      defaultValue = "${user.home}/.idris-maven/packages")
  protected File packagesHome;

  /**
   * The Idris package file ({@code .ipkg}) to build. Defaults to the single {@code .ipkg}
   * found in the source directory.
   */
  @Parameter(property = "idris.packageFile")
  protected File packageFile;

  /** Directory containing the Idris sources and package file. */
  @Parameter(property = "idris.sourceDirectory", defaultValue = "${project.basedir}")
  protected File sourceDirectory;

  /** JVM options passed to the forked compiler process. */
  @Parameter(property = "idris.jvmArgs", defaultValue = "-Xss92m -Xms3g -Xmx3g")
  protected String jvmArgs;

  /** Additional package search paths appended to {@code IDRIS2_PATH}. */
  @Parameter
  protected List<String> packagePaths;

  /** Extra environment variables for the forked process. */
  @Parameter
  protected Map<String, String> environmentVariables;

  /** Skips this goal entirely when {@code true}. */
  @Parameter(property = "idris.skip", defaultValue = "false")
  protected boolean skip;

  /**
   * How to launch the Idris compiler: the base command, environment entries specific to that
   * launch style, and extra {@code IDRIS2_PACKAGE_PATH} entries it requires.
   */
  private record CompilerLaunch(List<String> command,
                                Map<String, String> environment,
                                List<Path> packageSearchPath) {
  }

  /** Root of the extracted compiler distribution (the folder containing {@code exec/} and {@code env/}). */
  protected Path resolveCompilerRoot() throws MojoExecutionException {
    var version = effectiveCompilerVersion();
    var cacheDir = compilerHome.toPath().resolve(version);
    var cached = findDistributionRoot(cacheDir);
    return cached.map(this::logAndReturn).getOrElseTry(() -> unpackCompiler(version, cacheDir));
  }

  private Path logAndReturn(Path cachedCompiler) {
    getLog().debug("Using cached Idris compiler at " + cachedCompiler);
    return cachedCompiler;
  }

  private Path unpackCompiler(String version, Path cacheDir) throws MojoExecutionException {
    var zip = resolveCompilerZip(version);
    getLog().info("Unpacking Idris compiler " + version + " to " + cacheDir);
    try {
      Files.createDirectories(cacheDir);
      unzip(zip.toPath(), cacheDir);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to unpack Idris compiler distribution " + zip, e);
    }
    return findDistributionRoot(cacheDir).getOrElseThrow(() -> new MojoExecutionException(
        "Unpacked compiler at " + cacheDir + " does not contain an exec/ directory"));
  }

  private String effectiveCompilerVersion() throws MojoExecutionException {
    return configuredCompilerVersion()
        .or(this::runtimeDependencyVersion)
        .orElseThrow(() -> new MojoExecutionException(
            "Cannot determine the Idris compiler version. Either add a dependency on "
                + compilerGroupId + ":" + runtimeArtifactId
                + " or set <compilerVersion> (property idris.compilerVersion)."));
  }

  private Optional<String> configuredCompilerVersion() {
    return compilerVersion == null || compilerVersion.isBlank()
        ? Optional.empty()
        : Optional.of(compilerVersion);
  }

  private Optional<String> runtimeDependencyVersion() {
    return project.getDependencies().stream()
        .filter(dependency -> runtimeArtifactId.equals(dependency.getArtifactId())
            && compilerGroupId.equals(dependency.getGroupId()))
        .findFirst()
        .map(this::loggedRuntimeVersion);
  }

  private String loggedRuntimeVersion(Dependency dependency) {
    getLog().debug("Derived Idris compiler version %s from %s:%s".formatted(dependency.getVersion(), compilerGroupId, runtimeArtifactId));
    return dependency.getVersion();
  }

  private File resolveCompilerZip(String version) throws MojoExecutionException {
    var artifact = new DefaultArtifact(compilerGroupId, compilerArtifactId, "", "zip", version);
    var request = new ArtifactRequest(artifact, remoteRepositories, null);
    try {
      return repoSystem.resolveArtifact(repoSession, request).getArtifact().getPath().toFile();
    } catch (ArtifactResolutionException e) {
      throw new MojoExecutionException(
        "Failed to resolve Idris compiler distribution %s:%s:zip:%s".formatted(compilerGroupId, compilerArtifactId, version), e);
    }
  }

  /** Locate the distribution root under {@code base}: itself, or its single {@code idris2-*} child. */
  private static Option<Path> findDistributionRoot(Path base) {
    return io.vavr.collection.Stream.of(base).appendAll(childrenOrEmpty(base))
        .find(dir -> Files.isDirectory(dir.resolve("exec")));
  }

  protected File resolvePackageFile() throws MojoExecutionException {
    return packageFile != null ? existingPackageFile(packageFile) : singlePackageFileIn(sourceDirectory);
  }

  private static File existingPackageFile(File ipkg) throws MojoExecutionException {
    if (!ipkg.isFile()) {
      throw new MojoExecutionException("Idris package file not found: " + ipkg);
    }
    return ipkg;
  }

  private static File singlePackageFileIn(File dir) throws MojoExecutionException {
    var candidates = childrenOrEmpty(dir.toPath()).stream()
        .filter(file -> file.getFileName().toString().endsWith(".ipkg"))
        .toList();
    return switch (candidates.size()) {
      case 0 -> throw new MojoExecutionException(
          "No .ipkg file found in " + dir + ". Set <packageFile> explicitly.");
      case 1 -> candidates.get(0).toFile();
      default -> throw new MojoExecutionException(
          "Multiple .ipkg files found in " + dir + ". Set <packageFile> explicitly.");
    };
  }

  /**
   * The fully qualified entry-point class for a package's executable. The Idris JVM backend emits
   * it as {@code <executable>.JvmMain}, where {@code <executable>} is the package's
   * {@code executable} field.
   */
  protected String mainClassFor(File ipkg) throws MojoExecutionException {
    return readExecutableName(ipkg)
        .map(executable -> executable + ".JvmMain")
        .orElseThrow(() -> new MojoExecutionException(
            "Package " + ipkg.getName() + " has no 'executable' field; set the main class explicitly."));
  }

  /** The {@code executable = ...} field of an {@code .ipkg} file; empty when the field is absent. */
  private static Optional<String> readExecutableName(File ipkg) throws MojoExecutionException {
    return packageFileLines(ipkg).stream()
        .map(String::trim)
        .filter(line -> line.startsWith("executable") && line.contains("="))
        .findFirst()
        .map(AbstractIdrisMojo::executableFieldValue)
        .filter(value -> !value.isEmpty());
  }

  private static List<String> packageFileLines(File ipkg) throws MojoExecutionException {
    try {
      return Files.readAllLines(ipkg.toPath(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to read package file " + ipkg, e);
    }
  }

  private static String executableFieldValue(String line) {
    return unquote(line.substring(line.indexOf('=') + 1).trim());
  }

  private static String unquote(String value) {
    var quoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    return quoted ? value.substring(1, value.length() - 1) : value;
  }

  /**
   * Run the Idris compiler with the given arguments in {@code workingDirectory}, streaming its
   * output to the Maven log. Throws when the process exits with a non-zero status.
   */
  protected void runIdris(List<String> idrisArgs, File workingDirectory) throws MojoExecutionException {
    runIdris(idrisArgs, workingDirectory, null);
  }

  /**
   * Run the Idris compiler like {@link #runIdris(List, File)}, optionally overriding
   * {@code IDRIS2_PREFIX} — used by the {@code package} goal to make {@code --install} write into
   * a project-local staging directory. Since the prefix also anchors the compiler's package
   * search path, the distribution's own package directory (prelude, base, ...) is re-exposed via
   * {@code IDRIS2_PACKAGE_PATH} alongside the project's extracted Idris library dependencies.
   */
  protected void runIdris(List<String> idrisArgs, File workingDirectory, Path prefixOverride)
      throws MojoExecutionException {
    var launch = idrisExecutable != null && !idrisExecutable.isBlank()
        ? externalCompilerLaunch(prefixOverride)
        : resolvedCompilerLaunch(prefixOverride);
    var command = Stream.concat(launch.command().stream(), idrisArgs.stream()).toList();
    exec(command, workingDirectory, processEnvironment(launch), "idris");
  }

  private CompilerLaunch externalCompilerLaunch(Path prefixOverride) {
    if (prefixOverride == null) {
      return new CompilerLaunch(List.of(idrisExecutable), Map.of(), List.of());
    }
    getLog().warn("Overriding IDRIS2_PREFIX to " + prefixOverride
        + " for an external idris executable; its standard library must be made"
        + " findable via <packagePaths> or an IDRIS2_PACKAGE_PATH entry in"
        + " <environmentVariables>.");
    return new CompilerLaunch(List.of(idrisExecutable),
        Map.of("IDRIS2_PREFIX", prefixOverride.toString()), List.of());
  }

  private CompilerLaunch resolvedCompilerLaunch(Path prefixOverride) throws MojoExecutionException {
    var compilerRoot = resolveCompilerRoot();
    var appDir = compilerRoot.resolve("exec").resolve("idris2_app");
    // The distribution keeps loose .class files directly in idris2_app plus dependency
    // jars alongside them, so both the directory and its /* wildcard must be on the path.
    var classpath = appDir + File.pathSeparator + appDir + File.separator + "*";
    var command = new ArrayList<String>();
    command.add(javaExecutable());
    command.addAll(splitArgs(jvmArgs));
    command.addAll(List.of("-cp", classpath, COMPILER_MAIN_CLASS));
    var prefix = prefixOverride != null ? prefixOverride : compilerRoot.resolve("env");
    var extraSearchPath = prefixOverride != null
        ? List.of(compilerGlobalPackageDir(compilerRoot))
        : List.<Path>of();
    return new CompilerLaunch(command, Map.of("IDRIS2_PREFIX", prefix.toString()), extraSearchPath);
  }

  private Map<String, String> processEnvironment(CompilerLaunch launch) throws MojoExecutionException {
    var environment = new LinkedHashMap<String, String>();
    environment.put("IDRIS2_CG", "jvm");
    environment.putAll(launch.environment());
    idrisPath().ifPresent(path -> environment.put("IDRIS2_PATH", path));
    packageSearchPath(launch).ifPresent(path -> environment.put("IDRIS2_PACKAGE_PATH", path));
    environment.putAll(configuredEnvironment());
    return environment;
  }

  private Optional<String> idrisPath() {
    return packagePaths == null || packagePaths.isEmpty()
        ? Optional.empty()
        : Optional.of(String.join(File.pathSeparator, packagePaths));
  }

  private Optional<String> packageSearchPath(CompilerLaunch launch) throws MojoExecutionException {
    var entries = Stream.concat(extractIdrisDependencies().stream(), launch.packageSearchPath().stream())
        .map(Path::toString)
        .toList();
    return entries.isEmpty() ? Optional.empty() : Optional.of(String.join(File.pathSeparator, entries));
  }

  /** Extra environment variables configured for forked processes; empty when none. */
  protected Map<String, String> configuredEnvironment() {
    return environmentVariables != null ? environmentVariables : Map.of();
  }

  /**
   * Fork {@code command} in {@code workingDirectory} with the given extra environment, streaming
   * output to the Maven log under {@code logPrefix}. Throws on a non-zero exit status.
   */
  protected void exec(List<String> command, File workingDirectory,
                      Map<String, String> env, String logPrefix) throws MojoExecutionException {
    getLog().debug("Running: " + String.join(" ", command));
    getLog().debug("Environment: " + env);
    var builder = new ProcessBuilder(command)
        .directory(workingDirectory)
        .redirectErrorStream(true);
    builder.environment().putAll(env);
    var exit = runProcess(builder, logPrefix);
    if (exit != 0) {
      throw new MojoExecutionException(logPrefix + " exited with status " + exit);
    }
  }

  private int runProcess(ProcessBuilder builder, String logPrefix) throws MojoExecutionException {
    try {
      var process = builder.start();
      relayOutput(process, logPrefix);
      return process.waitFor();
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to run " + logPrefix, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("Interrupted while running " + logPrefix, e);
    }
  }

  private void relayOutput(Process process, String logPrefix) throws IOException {
    try (var reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      reader.lines().forEach(line -> getLog().info("[" + logPrefix + "] " + line));
    }
  }

  protected static String javaExecutable() {
    var candidate = new File(System.getProperty("java.home"), "bin" + File.separator + "java");
    return candidate.isFile() ? candidate.getAbsolutePath() : "java";
  }

  /** Naive whitespace split for JVM arg strings; adequate for typical {@code -Xmx}-style flags. */
  protected static List<String> splitArgs(String args) {
    return args == null || args.isBlank() ? List.of() : List.of(args.trim().split("\\s+"));
  }

  /**
   * Extract the project's Idris library dependencies (artifacts with classifier {@code idris} and
   * type {@code zip}) into the local package cache, returning one directory per dependency. Each
   * directory contains {@code <pkgname>-<pkgversion>/} package dirs and is suitable as an
   * {@code IDRIS2_PACKAGE_PATH} entry.
   */
  protected List<Path> extractIdrisDependencies() throws MojoExecutionException {
    return project.getArtifacts().stream()
        .filter(AbstractIdrisMojo::isIdrisPackage)
        .map(CheckedFunction1.of(this::extractIdrisDependency).unchecked())
        .toList();
  }

  private static boolean isIdrisPackage(Artifact artifact) {
    return IDRIS_CLASSIFIER.equals(artifact.getClassifier())
        && IDRIS_PACKAGE_TYPE.equals(artifact.getType());
  }

  private Path extractIdrisDependency(Artifact artifact) throws MojoExecutionException {
    try {
      return cachedOrExtracted(artifact);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to extract Idris dependency " + artifact, e);
    }
  }

  private Path cachedOrExtracted(Artifact artifact) throws IOException, MojoExecutionException {
    var zip = resolvedFile(artifact);
    var dir = packageCacheDir(artifact);
    var stamp = sourceStamp(zip);
    if (isUpToDate(dir, stamp)) {
      getLog().debug("Using cached Idris package at " + dir);
      return dir;
    }
    extractToCache(zip.toPath(), dir, stamp);
    getLog().info("Extracted Idris package " + artifact.getGroupId() + ":"
        + artifact.getArtifactId() + ":" + artifact.getBaseVersion() + " to " + dir);
    return dir;
  }

  private static File resolvedFile(Artifact artifact) throws MojoExecutionException {
    var zip = artifact.getFile();
    if (zip == null || !zip.isFile()) {
      throw new MojoExecutionException("Idris dependency " + artifact + " is not resolved to"
          + " a file. In a multi-module build, run at least the 'package' phase"
          + " (e.g. 'mvn package') so the library's -idris.zip artifact exists.");
    }
    return zip;
  }

  private Path packageCacheDir(Artifact artifact) {
    return packagesHome.toPath()
        .resolve(artifact.getGroupId())
        .resolve(artifact.getArtifactId())
        .resolve(artifact.getBaseVersion());
  }

  /** Cache-freshness stamp of an extracted zip: absolute path, size, and last-modified time. */
  private static String sourceStamp(File zip) {
    return zip.getAbsolutePath() + "|" + zip.length() + "|" + zip.lastModified();
  }

  private boolean isUpToDate(Path dir, String stamp) throws IOException {
    var stampFile = dir.resolve(SOURCE_MARKER);
    return Files.isRegularFile(stampFile)
        && stamp.equals(Files.readString(stampFile, StandardCharsets.UTF_8));
  }

  /**
   * Extract into a temp sibling and move into place, so concurrent builds never see a
   * half-extracted package.
   */
  private static void extractToCache(Path zip, Path dir, String stamp) throws IOException {
    var tmp = dir.resolveSibling(dir.getFileName() + ".tmp-" + System.nanoTime());
    Files.createDirectories(tmp);
    unzip(zip, tmp);
    Files.writeString(tmp.resolve(SOURCE_MARKER), stamp, StandardCharsets.UTF_8);
    deleteRecursively(dir);
    moveIntoPlace(tmp, dir);
  }

  private static void moveIntoPlace(Path tmp, Path dir) throws IOException {
    try {
      Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException moveFailed) {
      discardAfterLostRace(tmp, dir, moveFailed);
    }
  }

  /** A failed move usually means a concurrent build installed its copy first; keep that copy. */
  private static void discardAfterLostRace(Path tmp, Path dir, IOException moveFailed) throws IOException {
    deleteRecursively(tmp);
    if (!Files.isDirectory(dir)) {
      throw moveFailed;
    }
  }

  /**
   * The compiler distribution's global package directory ({@code env/idris2-<apiVersion>}),
   * holding prelude, base, etc. The directory is named after the embedded Idris2 API version,
   * which does not necessarily match the distribution version, so it is located by globbing.
   */
  protected Path compilerGlobalPackageDir(Path compilerRoot) throws MojoExecutionException {
    var envDir = compilerRoot.resolve("env");
    try {
      return children(envDir).stream()
          .filter(Files::isDirectory)
          .filter(dir -> dir.getFileName().toString().startsWith("idris2-"))
          .findFirst()
          .orElseThrow(() -> new MojoExecutionException(
              "No idris2-* package directory found under " + envDir));
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to list " + envDir, e);
    }
  }

  /**
   * Copy the {@code .class} files emitted under {@code <workingDir>/build/exec/*_app} into
   * {@code outputDirectory}. Returns the number of files copied.
   */
  protected int copyCompiledClasses(File workingDirectory, File outputDirectory)
      throws MojoExecutionException {
    var execDir = workingDirectory.toPath().resolve("build").resolve("exec");
    try {
      return Files.isDirectory(execDir)
          ? copyAppClassTrees(execDir, outputDirectory.toPath())
          : warnNoIdrisOutput(execDir);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to copy compiled Idris classes", e);
    }
  }

  private int warnNoIdrisOutput(Path execDir) {
    getLog().warn("No Idris output directory at " + execDir
        + " (nothing to copy). Is this an executable package?");
    return 0;
  }

  private static int copyAppClassTrees(Path execDir, Path outputDir) throws IOException {
    Files.createDirectories(outputDir);
    return children(execDir).stream()
        .filter(Files::isDirectory)
        .filter(dir -> dir.getFileName().toString().endsWith("_app"))
        .map(CheckedFunction1.of((Path appDir) -> copyClassTree(appDir, outputDir)).unchecked())
        .mapToInt(Integer::intValue)
        .sum();
  }

  private static int copyClassTree(Path appDir, Path outputDir) throws IOException {
    var classFiles = classFilesUnder(appDir);
    classFiles.forEach(CheckedConsumer.of(
        (Path classFile) -> copyRelative(appDir, classFile, outputDir)).unchecked());
    return classFiles.size();
  }

  private static List<Path> classFilesUnder(Path appDir) throws IOException {
    try (var tree = Files.walk(appDir)) {
      return tree
          .filter(Files::isRegularFile)
          .filter(file -> file.getFileName().toString().endsWith(".class"))
          .toList();
    }
  }

  private static void copyRelative(Path sourceRoot, Path file, Path targetRoot) throws IOException {
    var target = targetRoot.resolve(sourceRoot.relativize(file).toString());
    Files.createDirectories(target.getParent());
    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Zip the contents of {@code sourceDir} (not the directory itself) into {@code zipFile}. */
  protected static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
    Files.createDirectories(zipFile.getParent());
    var files = sortedRegularFilesUnder(sourceDir);
    try (var zipStream = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      files.forEach(CheckedConsumer.of(
          (Path file) -> writeZipEntry(zipStream, sourceDir, file)).unchecked());
    }
  }

  private static List<Path> sortedRegularFilesUnder(Path dir) throws IOException {
    try (var tree = Files.walk(dir)) {
      return tree.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static void writeZipEntry(ZipOutputStream zipStream, Path sourceDir, Path file)
      throws IOException {
    var entryName = sourceDir.relativize(file).toString().replace(File.separatorChar, '/');
    zipStream.putNextEntry(new ZipEntry(entryName));
    Files.copy(file, zipStream);
    zipStream.closeEntry();
  }

  protected static void deleteRecursively(Path dir) throws IOException {
    if (Files.exists(dir)) {
      deepestFirst(dir).forEach(CheckedConsumer.of(Files::delete).unchecked());
    }
  }

  private static List<Path> deepestFirst(Path dir) throws IOException {
    try (var tree = Files.walk(dir)) {
      return tree.sorted(Comparator.reverseOrder()).toList();
    }
  }

  private static void unzip(Path zip, Path targetDir) throws IOException {
    var normalizedTarget = targetDir.normalize();
    try (var zipFile = new ZipFile(zip.toFile())) {
      zipFile.stream().forEach(CheckedConsumer.of(
          (ZipEntry entry) -> extractEntry(zipFile, entry, normalizedTarget)).unchecked());
    }
  }

  private static void extractEntry(ZipFile zipFile, ZipEntry entry, Path targetDir) throws IOException {
    var target = targetDir.resolve(entry.getName()).normalize();
    if (!target.startsWith(targetDir)) {
      throw new IOException("Zip entry escapes target directory: " + entry.getName());
    }
    writeEntry(zipFile, entry, target);
  }

  private static void writeEntry(ZipFile zipFile, ZipEntry entry, Path target) throws IOException {
    if (entry.isDirectory()) {
      Files.createDirectories(target);
    } else {
      writeFileEntry(zipFile, entry, target);
    }
  }

  private static void writeFileEntry(ZipFile zipFile, ZipEntry entry, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    try (var in = zipFile.getInputStream(entry)) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Immediate children of {@code dir}. */
  protected static List<Path> children(Path dir) throws IOException {
    try (var entries = Files.list(dir)) {
      return entries.toList();
    }
  }

  /** Like {@link #children} but treats a missing or unlistable directory as empty. */
  protected static List<Path> childrenOrEmpty(Path dir) {
    try {
      return children(dir);
    } catch (IOException e) {
      // Callers use this where an absent directory is a normal state (e.g. nothing cached yet).
      return List.of();
    }
  }
}
