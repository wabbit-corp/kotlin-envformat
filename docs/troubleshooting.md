# Troubleshooting

## Native Decode Returns Defaults Or Missing Values

On Native targets, `platformEnvironment()` currently returns an empty map. Pass `env` explicitly when
decoding configuration that must work across targets.

## Missing List Elements

When a list count key is present, every indexed element up to that count must be present. Without a
count key, decoding stops at the first missing index.

## Map Keys Collide With Separators

Keep `escapeMapKeys = true` unless you control all map keys. With escaping disabled, keys should use
only letters, digits, `-`, `.`, `/`, `+`, and `:`, and they must not contain the configured
separator.

## Defaults Are Not Encoded

`encodeDefaults` defaults to `false`. Pass `encodeDefaults = true` or configure
`Env.Config(encodeDefaults = true)` when you want default-valued properties emitted.
