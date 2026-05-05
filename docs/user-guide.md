# User Guide

`kotlin-envformat` is a `kotlinx.serialization` format that flattens configuration models into
environment-variable maps.

## Decode From A Map

```kotlin
import kotlinx.serialization.Serializable
import one.wabbit.envformat.Env

@Serializable
data class AppConfig(
    val debug: Boolean = false,
    val databaseHost: String,
)

val config =
    Env.decode<AppConfig>(
        prefix = "APP",
        env = mapOf(
            "APP__DEBUG" to "true",
            "APP__DATABASE_HOST" to "db.internal",
        ),
    )

check(config.databaseHost == "db.internal")
```

Use the map overload in tests and shared code when you need deterministic behavior across targets.

## Encode To A Map

```kotlin
val encoded = Env.encodeToMap(
    AppConfig(debug = true, databaseHost = "db.internal"),
    prefix = "APP",
)

check(encoded["APP__DEBUG"] == "true")
check(encoded["APP__DATABASE_HOST"] == "db.internal")
```

## Naming

By default, property names are transformed to `SCREAMING_SNAKE_CASE` and path segments are joined
with `__`. A prefix is prepended before the field path.

`Env.Config` lets callers change the separator, name transform, enum encoding, list count emission,
map key escaping, and default-value encoding behavior.

## Lists And Maps

Lists use numeric path segments. The encoder writes a count by default:

```text
APP__PORTS__COUNT=2
APP__PORTS__0=5432
APP__PORTS__1=5433
```

`listCountSuffix` defaults to `_COUNT`. The leading underscore is stripped before joining with the
separator, so the default count segment is `COUNT`.

Maps use encoded map keys as path segments. By default, map keys are percent-encoded so underscores,
percent signs, separators, and list indexes do not collide.

If you disable map-key escaping, keys should use only letters, digits, `-`, `.`, `/`, `+`, and `:`,
and they must not contain the configured separator.

## Platform Environment

`Env.decode<T>()` without an explicit map reads `platformEnvironment()`. JVM and Android use
`System.getenv()`. Native currently returns an empty map, so pass `env` explicitly for portable
configuration loading.
