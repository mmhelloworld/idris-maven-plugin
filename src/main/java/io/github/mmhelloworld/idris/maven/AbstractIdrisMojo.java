package io.github.mmhelloworld.idris.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Common configuration and helpers shared by the Idris goals: locating the compiler
 * (resolved from Maven or an external executable) and forking it as a child process.
 */
public abstract class AbstractIdrisMojo extends AbstractMojo {

    /** Main class of the Idris JVM compiler inside the distribution's {@code exec/idris2_app}. */
    private static final String COMPILER_MAIN_CLASS = "idris2.JvmMain";

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

    @Parameter(property = "idris.skip", defaultValue = "false")
    protected boolean skip;

    // ---------------------------------------------------------------------------------------------
    // Compiler location
    // ---------------------------------------------------------------------------------------------

    /** Root of the extracted compiler distribution (the folder containing {@code exec/} and {@code env/}). */
    protected Path resolveCompilerRoot() throws MojoExecutionException {
        String version = effectiveCompilerVersion();
        File cacheDir = new File(compilerHome, version);
        Path root = findDistributionRoot(cacheDir.toPath());
        if (root != null) {
            getLog().debug("Using cached Idris compiler at " + root);
            return root;
        }

        File zip = resolveCompilerZip(version);
        getLog().info("Unpacking Idris compiler " + version + " to " + cacheDir);
        try {
            Files.createDirectories(cacheDir.toPath());
            unzip(zip.toPath(), cacheDir.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to unpack Idris compiler distribution " + zip, e);
        }

        root = findDistributionRoot(cacheDir.toPath());
        if (root == null) {
            throw new MojoExecutionException(
                "Unpacked compiler at " + cacheDir + " does not contain an exec/ directory");
        }
        return root;
    }

    private String effectiveCompilerVersion() throws MojoExecutionException {
        if (compilerVersion != null && !compilerVersion.isBlank()) {
            return compilerVersion;
        }
        for (Dependency dependency : project.getDependencies()) {
            if (runtimeArtifactId.equals(dependency.getArtifactId())
                && compilerGroupId.equals(dependency.getGroupId())) {
                getLog().debug("Derived Idris compiler version " + dependency.getVersion()
                    + " from " + compilerGroupId + ":" + runtimeArtifactId);
                return dependency.getVersion();
            }
        }
        throw new MojoExecutionException(
            "Cannot determine the Idris compiler version. Either add a dependency on "
                + compilerGroupId + ":" + runtimeArtifactId
                + " or set <compilerVersion> (property idris.compilerVersion).");
    }

    private File resolveCompilerZip(String version) throws MojoExecutionException {
        Artifact artifact = new DefaultArtifact(
            compilerGroupId, compilerArtifactId, "", "zip", version);
        ArtifactRequest request = new ArtifactRequest(artifact, remoteRepositories, null);
        try {
            ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
            return result.getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(
                "Failed to resolve Idris compiler distribution "
                    + compilerGroupId + ":" + compilerArtifactId + ":zip:" + version, e);
        }
    }

    /** Locate the distribution root under {@code base}: itself, or its single {@code idris2-*} child. */
    private Path findDistributionRoot(Path base) {
        if (!Files.isDirectory(base)) {
            return null;
        }
        if (Files.isDirectory(base.resolve("exec"))) {
            return base;
        }
        try (Stream<Path> children = Files.list(base)) {
            return children
                .filter(p -> Files.isDirectory(p.resolve("exec")))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Package file
    // ---------------------------------------------------------------------------------------------

    protected File resolvePackageFile() throws MojoExecutionException {
        if (packageFile != null) {
            if (!packageFile.isFile()) {
                throw new MojoExecutionException("Idris package file not found: " + packageFile);
            }
            return packageFile;
        }
        File[] candidates = sourceDirectory.listFiles((dir, name) -> name.endsWith(".ipkg"));
        if (candidates == null || candidates.length == 0) {
            throw new MojoExecutionException(
                "No .ipkg file found in " + sourceDirectory + ". Set <packageFile> explicitly.");
        }
        if (candidates.length > 1) {
            throw new MojoExecutionException(
                "Multiple .ipkg files found in " + sourceDirectory + ". Set <packageFile> explicitly.");
        }
        return candidates[0];
    }

    /**
     * The fully qualified entry-point class for a package's executable. The Idris JVM backend emits
     * it as {@code <executable>.JvmMain}, where {@code <executable>} is the package's
     * {@code executable} field.
     */
    protected String mainClassFor(File ipkg) throws MojoExecutionException {
        String executable = readExecutableName(ipkg);
        if (executable == null) {
            throw new MojoExecutionException(
                "Package " + ipkg.getName() + " has no 'executable' field; set the main class explicitly.");
        }
        return executable + ".JvmMain";
    }

    /** Read the {@code executable = ...} field from an {@code .ipkg} file, or {@code null} if absent. */
    protected String readExecutableName(File ipkg) throws MojoExecutionException {
        try {
            for (String raw : Files.readAllLines(ipkg.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("executable") && line.contains("=")) {
                    String value = line.substring(line.indexOf('=') + 1).trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value.isEmpty() ? null : value;
                }
            }
            return null;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read package file " + ipkg, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Process execution
    // ---------------------------------------------------------------------------------------------

    /**
     * Run the Idris compiler with the given arguments in {@code workingDirectory}, streaming its
     * output to the Maven log. Throws when the process exits with a non-zero status.
     */
    protected void runIdris(List<String> idrisArgs, File workingDirectory) throws MojoExecutionException {
        List<String> command = new ArrayList<>();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("IDRIS2_CG", "jvm");

        if (idrisExecutable != null && !idrisExecutable.isBlank()) {
            command.add(idrisExecutable);
        } else {
            Path root = resolveCompilerRoot();
            Path appDir = root.resolve("exec").resolve("idris2_app");
            env.put("IDRIS2_PREFIX", root.resolve("env").toString());
            command.add(javaExecutable());
            command.addAll(splitArgs(jvmArgs));
            command.add("-cp");
            // The distribution keeps loose .class files directly in idris2_app plus dependency
            // jars alongside them, so both the directory and its /* wildcard must be on the path.
            command.add(appDir + File.pathSeparator + appDir + File.separator + "*");
            command.add(COMPILER_MAIN_CLASS);
        }
        command.addAll(idrisArgs);

        String idrisPath = buildIdrisPath();
        if (idrisPath != null) {
            env.put("IDRIS2_PATH", idrisPath);
        }
        if (environmentVariables != null) {
            env.putAll(environmentVariables);
        }

        exec(command, workingDirectory, env, "idris");
    }

    /**
     * Fork {@code command} in {@code workingDirectory} with the given extra environment, streaming
     * output to the Maven log under {@code logPrefix}. Throws on a non-zero exit status.
     */
    protected void exec(List<String> command, File workingDirectory,
                        Map<String, String> env, String logPrefix) throws MojoExecutionException {
        getLog().debug("Running: " + String.join(" ", command));
        getLog().debug("Environment: " + env);

        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true);
        if (env != null) {
            builder.environment().putAll(env);
        }

        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLog().info("[" + logPrefix + "] " + line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new MojoExecutionException(logPrefix + " exited with status " + exit);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to run " + logPrefix, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while running " + logPrefix, e);
        }
    }

    protected static String javaExec() {
        return javaExecutable();
    }

    private String buildIdrisPath() {
        if (packagePaths == null || packagePaths.isEmpty()) {
            return null;
        }
        return String.join(File.pathSeparator, packagePaths);
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exe = "bin" + File.separator + "java";
        File candidate = new File(javaHome, exe);
        return candidate.isFile() ? candidate.getAbsolutePath() : "java";
    }

    /** Naive whitespace split for JVM arg strings; adequate for typical {@code -Xmx}-style flags. */
    private static List<String> splitArgs(String args) {
        if (args == null || args.isBlank()) {
            return List.of();
        }
        return Stream.of(args.trim().split("\\s+")).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------------------------------
    // Output handling
    // ---------------------------------------------------------------------------------------------

    /**
     * Copy the {@code .class} files emitted under {@code <workingDir>/build/exec/*_app} into
     * {@code outputDirectory}. Returns the number of files copied.
     */
    protected int copyCompiledClasses(File workingDirectory, File outputDirectory)
        throws MojoExecutionException {
        Path execDir = workingDirectory.toPath().resolve("build").resolve("exec");
        if (!Files.isDirectory(execDir)) {
            getLog().warn("No Idris output directory at " + execDir
                + " (nothing to copy). Is this an executable package?");
            return 0;
        }
        try {
            Files.createDirectories(outputDirectory.toPath());
            List<Path> appDirs;
            try (Stream<Path> children = Files.list(execDir)) {
                appDirs = children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith("_app"))
                    .collect(Collectors.toList());
            }
            int count = 0;
            for (Path appDir : appDirs) {
                count += copyClassTree(appDir, outputDirectory.toPath());
            }
            return count;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy compiled Idris classes", e);
        }
    }

    private int copyClassTree(Path appDir, Path outputDir) throws IOException {
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(appDir)) {
            classFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .collect(Collectors.toList());
        }
        for (Path classFile : classFiles) {
            Path relative = appDir.relativize(classFile);
            Path target = outputDir.resolve(relative.toString());
            Files.createDirectories(target.getParent());
            Files.copy(classFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return classFiles.size();
    }

    // ---------------------------------------------------------------------------------------------
    // Zip extraction
    // ---------------------------------------------------------------------------------------------

    private static void unzip(Path zip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.normalize();
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    protected static Path path(String first, String... more) {
        return Paths.get(first, more);
    }
}
