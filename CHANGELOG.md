# Changelog

All notable changes to moxy-ksp will be documented in this file.

## [1.0.2-1.5.6] - 2026-08-01

### Fixed

- Vararg view methods generated a plain, non-vararg `Array` override parameter instead of a real
  Kotlin `vararg` — inferred from the *shape* of the resolved parameter type, which KSP does not
  actually specify for varargs (KSP1 returns the array type, KSP2 the element type). This meant:
  Kotlin callers couldn't use natural vararg call syntax; a Java `Object... args` source produced
  an invariant, non-null `Array<Any>` instead of the correct `Array<out Any?>`; and a
  Kotlin-declared `vararg t: T` view method couldn't be overridden at all — a flat compile error,
  masked so far only because every vararg view method in practice happens to be Java-declared.
  Fixed by reading vararg-ness directly from `KSValueParameter.isVararg` (unambiguous, engine
  -independent) and normalizing the resolved type to its element via a documented `varargElement()`
  helper — not an engine check, since `KModifier.VARARG` re-adds the array-ness on either shape.
  Added regression tests for the Java-source, Kotlin-declared, generic-substitution, and
  overload-alongside-non-vararg cases (all four fail against the pre-fix code), plus a sample
  module fixture exercising real vararg call syntax end-to-end.

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
