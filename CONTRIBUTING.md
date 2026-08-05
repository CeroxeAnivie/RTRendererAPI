# Contributing

## Before opening a pull request

Use JDK 25 to build the Java 21 public ABI and the checked-in Gradle wrapper. Keep changes within one ownership boundary and do not update ABI baselines to hide an unexplained difference.

```powershell
.\gradlew.bat clean check assemble --dependency-verification=strict --no-daemon --console=plain
```

Native RTX acceptance requires the documented SDK roots and a trusted Windows RTX runner. Fork pull requests must not execute on the self-hosted GPU runner; merge queue or a maintainer-owned branch supplies that gate.

## API changes

- Preserve the compatibility levels in `docs/COMPATIBILITY.md`.
- Add public methods through builders, new types or interface default methods when required for binary compatibility.
- Add contract tests for empty, invalid, concurrent, cancellation and cleanup paths affected by the change.
- Update `previous_api_version` only after a formal Central release becomes the compatibility baseline.
- Never describe an implementation as active without typed execution evidence.

## Pull requests

Explain the root cause, behavioral contract, failure/rollback behavior, and verification performed. Generated files, local SDK paths, credentials, downloaded tools and build outputs must not be committed. Security-sensitive reports follow `SECURITY.md`, not the public review flow.

## Release flow

1. Select a SemVer version and update all checked version facts.
2. Pass deterministic checks, previous-release ABI compatibility, published-consumer verification and bounded RTX acceptance.
3. Build and verify the signed Central Portal bundle.
4. Publish through Maven Central and confirm all three modules are available.
5. Create the matching annotated `vMAJOR.MINOR.PATCH` source tag. GitHub Releases are not a binary distribution channel for this project.
