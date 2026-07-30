# moxy-ksp

A Kotlin Symbol Processing (KSP) port of [Moxy](https://github.com/Arello-Mobile/Moxy)'s
`moxy-compiler` — the annotation processor behind `@InjectPresenter`/`@InjectViewState`/etc.
Functionally targets `com.arello-mobile:moxy-compiler:1.5.6` (built from `origin/release/1.5.6` of
the upstream repo, not the newer but functionally-stale `master`).

## Usage

Pick **either** `moxy-compiler` (apt/kapt) or `moxy-ksp` for a given module — never both. Both
processors independently generate the same classes (`Foo$$State`, `Foo$$PresentersBinder`,
`Foo$$ViewStateProvider`, `MoxyReflector`) into the same packages; applying both to one module is a
duplicate-class compile error, not a safe combination. Different modules in the same multi-module
build may use different compilers — `@RegisterMoxyReflectorPackages` aggregation works across that
mix, since it only depends on each module's own `MoxyReflector` having the expected shape,
regardless of which compiler produced it.

```kotlin
plugins {
    kotlin("jvm") // or kotlin("android"), kotlin("multiplatform") — see limitation below
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.arello-mobile:moxy:1.5.6")
    ksp("ru.kode.moxy-ksp:moxy-ksp:1.0.0-1.5.6")
}
```

Version format is `<our version>-<targeted moxy version>` (mirrors the old `<kotlinVersion>-<kspVersion>`
KSP convention) — `1.0.0-1.5.6` is our 1.0.0 release, targeting `moxy` 1.5.6.

See `sample/` for a runnable, real-consumer usage example (applies the plugin from `mavenLocal()`,
not a `project()` dependency shortcut).

## Known limitation: pure-Java modules can't use this at all

KSP only runs as part of Kotlin compilation — there is no `kspKotlin` Gradle task, and no path to
invoke a `SymbolProcessorProvider`, in a module that never applies any Kotlin Gradle plugin. A
module with zero `.kt` files but the Kotlin plugin applied works fine (KSP still processes its Java
sources); a module with **no Kotlin plugin at all** does not.

This is an accepted, deliberate gap (not a bug to file) — such modules should stay on
`moxy-compiler` (apt/kapt), or add the Kotlin Gradle plugin with zero `.kt` files purely to gain a
`kspKotlin` task.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

MIT — see [LICENSE](LICENSE).
