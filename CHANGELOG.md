# Changelog

## 0.1.0 (unreleased)

Initial release.

- `idris:compile` — compiles Idris sources to JVM bytecode via the Idris JVM backend and copies the
  emitted classes into `target/classes`.
- `idris:run` — runs the compiled program using the project's runtime classpath.
- `idris:test` — builds and runs an Idris test package.
- `idris:package` — packages an Idris library as a Maven artifact: installs it into a
  project-local staging prefix and attaches the result as the `-idris.zip` classifier artifact.
- Idris library dependencies via Maven: `idris`-classifier zip dependencies are extracted into a
  local cache and exposed to the compiler through `IDRIS2_PACKAGE_PATH`, so `.ipkg` `depends`
  entries resolve; works transitively and inside multi-module reactors.
- Resolves the Idris JVM compiler distribution from Maven and caches it locally; the compiler
  version is derived from the declared `idris-jvm-runtime` dependency.
- Optional `idrisExecutable` escape hatch to use an installed `idris2` instead.
