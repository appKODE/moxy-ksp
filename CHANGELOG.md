# Changelog

All notable changes to moxy-ksp will be documented in this file.

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
- `sample/` — a runnable, real-consumer usage example resolving the published artifact from
  `mavenLocal()`.

### Known limitations

- KSP only runs as part of Kotlin compilation — a module with no Kotlin Gradle plugin applied at
  all (pure Java) cannot use `moxy-ksp`; see README.
