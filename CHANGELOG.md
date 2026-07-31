# Changelog

All notable changes to moxy-ksp will be documented in this file.

## [1.0.1-1.5.6] - 2026-08-01

### Fixed

- KSP2-only crash in `MoxyReflectorGenerator`: `SymbolProcessor.finish()` ran after every
  processing round had completed, but the reflector generator still dereferenced
  `KSClassDeclaration` objects retained from earlier rounds (`.qualifiedName`, `.toClassName()`,
  even `equals`/`hashCode`). Under KSP2's Analysis-API-backed symbols this throws
  `KaInvalidLifetimeOwnerAccessException: PSI has changed since creation` once a round's session
  has ended — most visibly from `groupPresenterBindersByRoot` when a presenter container extends
  another presenter container. Fixed by converting every symbol crossing a round boundary to a
  plain KotlinPoet `ClassName` at collection time, before it can go stale. Added a regression test
  covering the container-inheritance code path this exercises (note: it verifies the
  binder-grouping logic, not the KSP2 session-lifetime crash itself — that requires a real
  incremental multi-round Gradle build, which the `kotlin-compile-testing` harness doesn't
  reproduce).
- CI: `sample/` resolved its `moxy-ksp` dependency from `mavenLocal()`, which required a
  `publishToMavenLocal` step neither `ci.yml` nor `publish.yml` ever ran, breaking every green-CI
  requirement. Now resolved via Gradle dependency substitution to the root project (same pattern as
  `build-publish-plugin/example-project`), keeping the real published coordinate in
  `sample/build.gradle.kts` for realism without needing a local publish.

## [1.0.0-1.5.6] - 2026-07-31

Initial release — a Kotlin Symbol Processing port of `moxy-compiler`, functionally targeting
`com.arello-mobile:moxy-compiler:1.5.6` (built from `origin/release/1.5.6` of the upstream repo,
including its generic View/Presenter support and `MoxyReflectorGenerator` fix).

### Added

- `@InjectPresenter`/`@InjectViewState`/`@ProvidePresenter`/`@ProvidePresenterTag`/
  `@RegisterMoxyReflectorPackages` support, generating `$$State`, `$$PresentersBinder`,
  `$$ViewStateProvider`, and `MoxyReflector` classes — same shapes as the apt compiler.
- Generic View/Presenter resolution via `Resolver.asMemberOf` (KSP's direct equivalent of the
  apt original's `Types.asMemberOf`).
- All five `StateStrategy` implementations verified behaviorally (`AddToEndStrategy`,
  `AddToEndSingleStrategy`, `SingleStateStrategy`, `SkipStrategy`, `OneExecutionStateStrategy`).
- `sample/` — a runnable, real-consumer usage example resolving the published artifact.

### Known limitations

- KSP only runs as part of Kotlin compilation — a module with no Kotlin Gradle plugin applied at
  all (pure Java) cannot use `moxy-ksp`; see README.
