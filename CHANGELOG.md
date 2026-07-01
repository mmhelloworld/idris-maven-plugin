# Changelog

## 0.1.0 (unreleased)

Initial release.

- `idris:compile` — compiles Idris sources to JVM bytecode via the Idris JVM backend and copies the
  emitted classes into `target/classes`.
- `idris:run` — runs the compiled program using the project's runtime classpath.
- `idris:test` — builds and runs an Idris test package.
- Resolves the Idris JVM compiler distribution from Maven and caches it locally; the compiler
  version is derived from the declared `idris-jvm-runtime` dependency.
- Optional `idrisExecutable` escape hatch to use an installed `idris2` instead.
