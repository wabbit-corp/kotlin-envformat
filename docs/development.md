# Development

`kotlin-envformat` is a Kotlin Multiplatform serialization-format library. In the monorepo
workspace, build and test it through the `dev` tooling:

```bash
./dev build kotlin-envformat
```

From the project directory, or when working from a standalone checkout, run Gradle directly:

```bash
./gradlew build
```

## Documentation Standards

Public KDoc should state:

- whether an API reads the process environment or an explicit map
- how names, lists, maps, defaults, and enum values are encoded
- platform behavior for `platformEnvironment`
- decoding failure conditions

Generated Dokka output remains the source of truth for exact signatures and platform availability.
