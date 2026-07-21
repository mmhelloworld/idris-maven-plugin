# Idris Maven Plugin

A Maven plugin that compiles [Idris 2](https://www.idris-lang.org/) sources to JVM bytecode using
the [Idris JVM backend](https://github.com/mmhelloworld/idris-jvm), and integrates the result into
the standard Maven build lifecycle — so Idris code is packaged into your JAR like any other compiled
class.

The compiler is **resolved from Maven**, not from a global install. Following the model the Scala
Maven plugin uses with `scala-library`, the compiler version is derived from your declared
`idris-jvm-runtime` dependency, so the compiler that builds your code always matches the runtime you
link against. The compiler is downloaded once, unpacked into a local cache, and run in a forked
`java` process for classpath isolation from Maven itself.

## Requirements

- JDK 21+ (the plugin); the forked Idris compiler runs on the same JVM
- Maven 3.9+

## Quick start

```xml
<dependencies>
  <!-- The compiler version is derived from this dependency. -->
  <dependency>
    <groupId>io.github.mmhelloworld</groupId>
    <artifactId>idris-jvm-runtime</artifactId>
    <version>0.8.4</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>io.github.mmhelloworld</groupId>
      <artifactId>idris-maven-plugin</artifactId>
      <version>0.1.0</version>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

With an `.ipkg` in the project root (see [`examples/hello`](examples/hello)):

```
mvn package          # compiles Idris -> JVM classes and bundles them into the JAR
mvn idris:run        # runs the compiled program
```

## Goals

| Goal | Default phase | Description |
|------|---------------|-------------|
| `idris:compile` | `compile` | Runs `idris2 --build <package>.ipkg` with the JVM backend and copies the emitted classes into `target/classes`. |
| `idris:package` | `package` | Installs the Idris library into a project-local staging prefix, zips it, and attaches it as the `-idris.zip` classifier artifact for `mvn install`/`deploy` to publish. |
| `idris:run` | — | Runs the compiled program using the project's runtime classpath. |
| `idris:test` | `test` | Builds and runs an Idris test package (configure `<testPackageFile>`). |
| `idris:help` | — | Prints plugin documentation. |

## Configuration

Common parameters (all optional unless noted):

| Parameter | Property | Default | Notes |
|-----------|----------|---------|-------|
| `compilerVersion` | `idris.compilerVersion` | derived from `idris-jvm-runtime` dependency | Override the compiler version. |
| `packageFile` | `idris.packageFile` | the single `.ipkg` in `sourceDirectory` | The package to build. |
| `sourceDirectory` | `idris.sourceDirectory` | `${project.basedir}` | Directory containing the `.ipkg`. |
| `outputDirectory` | `idris.outputDirectory` | `${project.build.outputDirectory}` | Where compiled classes are copied (`compile` goal). |
| `compilerHome` | `idris.compilerHome` | `${user.home}/.idris-maven/compiler` | Cache for unpacked compiler distributions. |
| `packagesHome` | `idris.packagesHome` | `${user.home}/.idris-maven/packages` | Cache for extracted Idris library dependencies. |
| `stagingDirectory` | `idris.stagingDirectory` | `${project.build.directory}/idris/prefix` | Staging prefix used by `idris:package`. |
| `jvmArgs` | `idris.jvmArgs` | `-Xss92m -Xms3g -Xmx3g` | JVM options for the forked compiler. |
| `idrisExecutable` | `idris.executable` | — | Escape hatch: use an installed `idris2` instead of resolving from Maven. |
| `packagePaths` | — | — | Extra entries appended to `IDRIS2_PATH`. |
| `mainClass` | `idris.mainClass` | `<executable>.JvmMain` | Entry point for `idris:run`. |
| `skip` | `idris.skip` | `false` | Skip the goal. |

## Library dependencies

Idris libraries can be shared through Maven like any other artifact (see
[`examples/lib-and-app`](examples/lib-and-app)).

**Publishing a library.** Add the `package` goal to the library module; the library's `.ipkg`
declares its `modules` (no `main`/`executable`):

```xml
<execution>
  <goals>
    <goal>package</goal>
  </goals>
</execution>
```

`idris:package` runs `idris2 --install` into `target/idris/prefix`, zips the installed package
(ipkg metadata plus the checked `.ttc` tree), and attaches it as
`<artifactId>-<version>-idris.zip`. Maven's regular `install`/`deploy` phases then put it in the
local repository or publish it, exactly like a sources or javadoc jar.

**Consuming a library.** Declare it in two places — the Maven dependency (which fetches the zip)
and the `.ipkg` `depends` (which tells the compiler to use it):

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>mylib</artifactId>
  <version>1.0.0</version>
  <classifier>idris</classifier>
  <type>zip</type>
</dependency>
```

```
depends = mylib
```

Before each compile the plugin extracts every `idris`-classifier zip dependency into
`packagesHome` (re-extracting when a SNAPSHOT changes) and passes the directories to the compiler
via `IDRIS2_PACKAGE_PATH`. Idris dependencies are transitive: a library's own `idris`-classifier
zip dependencies (at compile scope) flow to its consumers automatically.

Notes:

- Producer and consumer should use the same `idris-jvm-runtime` version; a mismatch surfaces as an
  idris2 "TTC version" error.
- In a multi-module build, run at least the `package` phase at the aggregator (`mvn package`, not
  `mvn compile`) — the sibling's `-idris.zip` only exists after its own `package` phase.
- `idris:package` with the `idrisExecutable` escape hatch requires making that compiler's standard
  library findable manually (e.g. an `IDRIS2_PACKAGE_PATH` entry in `<environmentVariables>`),
  because overriding `IDRIS2_PREFIX` hides its default package directory.

## How it works

1. The plugin resolves `io.github.mmhelloworld:idris-jvm-compiler:<version>:zip` from your configured
   Maven repositories (Maven Central by default).
2. The distribution is unpacked and cached under `compilerHome/<version>/`. It contains the compiler
   (`exec/idris2_app`, main class `idris2.JvmMain`) and the bootstrapped standard library
   (`env/` → `IDRIS2_PREFIX`).
3. The compiler is invoked as
   `java <jvmArgs> -cp <idris2_app>:<idris2_app>/* idris2.JvmMain --build <package>.ipkg`
   with `IDRIS2_CG=jvm`, emitting `.class` files under `build/exec/<executable>_app`.
4. Those classes are copied into `target/classes` and packaged into the JAR by the standard Maven
   lifecycle.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
