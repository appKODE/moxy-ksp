# Changelog

All notable changes to moxy-ksp will be documented in this file.

## [1.0.5-1.5.6] - 2026-08-10

### Fixed

- **`overrides nothing` compile error on Java view parameters annotated `@NotNull`.** The 1.0.4
  platform-nullability fix trusted KSP's `Nullability` verdict alone, but whether a not-null
  annotation is reported as `NOT_NULL` or as `PLATFORM` varies with the annotation artifact and the
  KSP/Kotlin versions in use, while the Kotlin compiler reading the same Java source always honours
  it. Where the two disagreed the generated `$$State` declared the parameter nullable and no longer
  overrode the view method, failing the build (`'setChannelLogoName' overrides nothing`). A
  nullability annotation on the parameter now decides: `@NotNull`/`@NonNull`/`@Nonnull` generates a
  non-null parameter and `@Nullable`/`@CheckForNull` a nullable one, whatever KSP reports — the same
  mismatch is a compile error in both directions. Only genuinely unannotated Java parameters fall
  back to the platform-means-nullable rule, so the 1.0.4 fix still holds.

## [1.0.4-1.5.6] - 2026-08-10

### Fixed

- **Java-declared view interfaces crashed with `NullPointerException` when passed a null argument.**
  A Java method parameter resolves to a *platform* type, which KotlinPoet can only render as
  non-null, so the generated Kotlin `$$State` override (and its `Command` constructor) picked up an
  `Intrinsics.checkNotNullParameter` call that the apt original — which generated Java — never had.
  Calling `view.showText(null)` on a `void showText(String text)` view therefore blew up inside the
  generated state class instead of reaching the view. Platform parameters are now generated as
  nullable; parameters annotated `@Nullable`/`@NonNull` and anything Kotlin-declared keep their
  declared nullability, so no existing non-null contract is loosened.

## [1.0.3-1.5.6] - 2026-08-10

### Fixed

- Incremental builds emitted a **partial `MoxyReflector`**: it listed only the presenters,
  containers and strategies whose source files happened to be dirty in that build, and every
  untouched screen silently dropped out of the registry. Because moxy 1.5.6's `MvpProcessor` treats
  a generated `MoxyReflector` as authoritative — `getPresenterBinders(clazz)` returning nothing
  means no binder, with no error and no log — an unregistered Activity/Fragment simply got no
  presenter attached, surfacing later as `UninitializedPropertyAccessException: lateinit property
  mPresenter has not been initialized` or as a screen whose view methods are never called. Whether
  a given screen worked depended on which files you last edited; a clean build repaired it, so this
  never affected CI-built or released artifacts, only local incremental installs.
  The reflector is a whole-module aggregate, but every root function a processor can read state
  from (`Resolver.getSymbolsWithAnnotation`, and `getAllFiles()` too — it returns the dirty set, not
  the whole compilation) yields only dirty files, so it could only ever be built from the subset
  KSP handed over that round. Fixed by writing it with `Dependencies.ALL_FILES` instead of
  `aggregating = true`: KSP then treats every source file as an input of that output and re-dirties
  the whole module whenever it must be regenerated. This makes KSP reprocess the module on any
  change — a whole-module registry has no cheaper correct form, since KSP gives a processor no way
  to carry per-file state across builds. `ksp.incremental=false` is no longer needed as a
  workaround.
  Covered by `IncrementalReflectorTest`, which drives real multi-invocation Gradle builds through
  TestKit, one per kind of incremental input change: a **modified** source file, an **added** one,
  and a **removed** one. Each asserts that the regenerated reflector still registers everything
  declared in the files it did not touch — presenter, `@InjectPresenter` container and non-default
  strategy alike — and the removal case runs `build`, so the surviving generated sources must also
  still compile against the regenerated registry. Both tests fail against the pre-fix code, where
  the reflector collapses to just the edited file and `sPresenterBinders` comes out empty — the
  exact shape of the reported crash. TestKit rather than the existing `kotlin-compile-testing`
  suite because the latter deletes KSP's caches directory before every compilation, making each one
  a full rebuild in which this defect cannot occur.

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
