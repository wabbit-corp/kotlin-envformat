# API Reference

Generate exact signatures locally with:

```bash
./gradlew dokkaGeneratePublicationHtml
```

## Public Surface

- `platformEnvironment()`: returns the platform default environment map.
- `Env`: `SerialFormat` implementation for environment maps.
- `Env.Config`: naming, enum, list, map, and default-value configuration.
- `Env.MapMode`: map key encoding mode.
- `Env.decode`: instance overloads that decode from an explicit serializer or reified type.
- `Env.encodeToMap`: instance overloads that encode with an explicit serializer or reified type.
- `Env.Companion.decode`: convenience decode overloads using a temporary `Env`.
- `Env.Companion.encodeToMap`: convenience encode overloads using a temporary `Env`.

Decoding throws `SerializationException` for missing required values, invalid primitive values, and
invalid map-key percent escapes.

The default list count segment is configured as `_COUNT`, but a leading underscore is stripped before
joining with the separator, producing keys like `APP__ITEMS__COUNT`.
