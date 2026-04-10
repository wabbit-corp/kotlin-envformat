@file:OptIn(ExperimentalSerializationApi::class)

package one.wabbit.envformat

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * Returns the process environment for the current platform.
 *
 * On JVM and Android this delegates to `System.getenv()`.
 * On Native it currently returns an empty map.
 *
 * In shared code, prefer passing an explicit `env` map into [Env.decode] when you want
 * deterministic behavior across platforms and tests.
 */
expect fun platformEnvironment(): Map<String, String>

// -------- Public API --------

/**
 * Serializes typed configuration objects to environment-variable maps and decodes them back again.
 *
 * Default naming rules:
 * - properties become `SCREAMING_SNAKE_CASE`
 * - nested objects are joined with [Config.separator], which defaults to `__`
 * - list items are stored at indexed keys like `TAG__0`, with optional `TAG__COUNT`
 * - an optional prefix is prepended ahead of the field path
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class DbConfig(val host: String, val port: Int = 5432)
 *
 * val env = mapOf(
 *     "APP__HOST" to "db.internal",
 *     "APP__PORT" to "5433",
 * )
 *
 * val decoded = Env.decode<DbConfig>("APP", env)
 * check(decoded == DbConfig(host = "db.internal", port = 5433))
 * ```
 */
class Env(
    val config: Config = Config(),
    override val serializersModule: SerializersModule = EmptySerializersModule(),
) : SerialFormat {

    /** Controls how map keys are turned into path segments. */
    enum class MapMode {
        KEY_AS_PATH
    }

    /** Configuration knobs for environment naming and collection encoding. */
    data class Config(
        val separator: String = "__",
        val listCountSuffix: String = "_COUNT",
        val nameTransform: (String) -> String = Companion::defaultToEnvToken,
        val honorPretransformedNames: Boolean = true,
        /**
         * Should encoder include properties equals to their defaults? Mirrors Json { encodeDefaults
         * = ... }.
         */
        val encodeDefaults: Boolean = false,
        /** Should encoder emit <TAG>_COUNT for lists? (decoder doesn't require it) */
        val writeListCount: Boolean = true,
        /** Encode enums as their names (default) or ordinals. */
        val enumsAsNames: Boolean = true,
        val mapMode: MapMode = MapMode.KEY_AS_PATH,
        /**
         * Percent-encode map keys to avoid collisions with the separator and list indexes. Escapes
         * at least '_' and '%', so 'KEY_1' won't collide with list tags like '_1'.
         */
        val escapeMapKeys: Boolean = true,
        /** When map keys are enums, encode them as names (true) or ordinals (false). */
        val enumKeysAsNames: Boolean = true,
    )

    // --- Map key escaping (percent-encoding) ---
    private fun hex2(n: Int) = n.toString(16).uppercase().padStart(2, '0')

    internal fun encodeMapKeySegment(raw: String, cfg: Config): String {
        if (!cfg.escapeMapKeys) return raw
        val sep = cfg.separator
        val s = raw
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    out.append("%25")
                    i++
                }
                // Escape each '_' so list tags like _0 never collide with key characters
                c == '_' -> {
                    out.append("%5F")
                    i++
                }
                // If separator (e.g., "__") appears in the key, escape the run
                // character-by-character
                s.regionMatches(i, sep, 0, sep.length) -> {
                    repeat(sep.length) { j -> out.append('%').append(hex2(sep[j].code)) }
                    i += sep.length
                }
                // ASCII safe set; everything else percent-encode
                c.isLetterOrDigit() || c == '-' || c == '.' || c == '/' || c == '+' || c == ':' -> {
                    out.append(c)
                    i++
                }
                else -> {
                    out.append('%').append(hex2(c.code))
                    i++
                }
            }
        }
        return out.toString()
    }

    internal fun decodeMapKeySegment(enc: String, cfg: Config): String {
        if (!cfg.escapeMapKeys) return enc
        val s = enc
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                require(i + 2 < s.length) { "Invalid percent escape in map key segment: '$s'" }
                val hex = s.substring(i + 1, i + 3)
                val ch = hex.toInt(16).toChar()
                out.append(ch)
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // --- decoding ---

    /**
     * Decodes a serializable value of type [T] from environment variables.
     *
     * This overload resolves the serializer automatically.
     *
     * @param prefix Optional prefix such as `APP`.
     * @param env The environment map to read from. Defaults to [platformEnvironment].
     */
    inline fun <reified T> decode(
        prefix: String = "",
        env: Map<String, String> = platformEnvironment(),
    ): T = decode(serializer<T>(), prefix, env)

    /**
     * Decodes a serializable value from environment variables using an explicit serializer.
     *
     * @param strategy Serializer for the target type.
     * @param prefix Optional prefix such as `APP`.
     * @param env The environment map to read from. Defaults to [platformEnvironment].
     * @throws SerializationException when required values are missing or malformed.
     */
    fun <T> decode(
        strategy: DeserializationStrategy<T>,
        prefix: String = "",
        env: Map<String, String> = platformEnvironment(),
    ): T =
        EnvDecoder(
                env = env,
                basePath = prefix,
                cfg = config,
                serializersModule = serializersModule,
                containerKind = StructureKind.CLASS,
            )
            .decodeSerializableValue(strategy)

    // --- encoding ---

    /**
     * Encodes a serializable value into a flat environment-variable map.
     *
     * This overload resolves the serializer automatically.
     *
     * @param value Value to encode.
     * @param prefix Optional prefix such as `APP`.
     * @param encodeDefaults Whether properties equal to defaults should still be emitted.
     */
    inline fun <reified T> encodeToMap(
        value: T,
        prefix: String = "",
        encodeDefaults: Boolean = config.encodeDefaults,
    ): Map<String, String> = encodeToMap(serializer<T>(), value, prefix, encodeDefaults)

    /**
     * Encodes a serializable value into a flat environment-variable map using an explicit serializer.
     *
     * Example:
     * ```kotlin
     * @Serializable
     * data class DbConfig(val host: String, val port: Int = 5432)
     *
     * val encoded = Env.encodeToMap(DbConfig("db.internal", 5433), prefix = "APP")
     * check(encoded["APP__HOST"] == "db.internal")
     * check(encoded["APP__PORT"] == "5433")
     * ```
     *
     * @param strategy Serializer for the source type.
     * @param value Value to encode.
     * @param prefix Optional prefix such as `APP`.
     * @param encodeDefaults Whether properties equal to defaults should still be emitted.
     */
    fun <T> encodeToMap(
        strategy: SerializationStrategy<T>,
        value: T,
        prefix: String = "",
        encodeDefaults: Boolean = config.encodeDefaults,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        EnvEncoder(
                out = out,
                basePath = prefix,
                cfg = config.copy(encodeDefaults = encodeDefaults),
                serializersModule = serializersModule,
                containerKind = StructureKind.CLASS,
            )
            .encodeSerializableValue(strategy, value)
        return out
    }

    companion object {
        inline fun <reified T> decode(
            prefix: String = "",
            env: Map<String, String> = platformEnvironment(),
            config: Config = Config(),
        ): T = Env(config = config).decode(serializer<T>(), prefix, env)

        fun <T> decode(
            strategy: DeserializationStrategy<T>,
            prefix: String = "",
            env: Map<String, String> = platformEnvironment(),
            config: Config = Config(),
        ): T = Env(config = config).decode(strategy, prefix, env)

        inline fun <reified T> encodeToMap(
            value: T,
            prefix: String = "",
            config: Config = Config(),
            encodeDefaults: Boolean = config.encodeDefaults,
        ): Map<String, String> =
            Env(config = config).encodeToMap(serializer<T>(), value, prefix, encodeDefaults)

        fun <T> encodeToMap(
            strategy: SerializationStrategy<T>,
            value: T,
            prefix: String = "",
            config: Config = Config(),
            encodeDefaults: Boolean = config.encodeDefaults,
        ): Map<String, String> = Env(config = config).encodeToMap(strategy, value, prefix, encodeDefaults)

        // Utility — manual CamelCase -> SCREAMING_SNAKE
        private val SCREAMING_SNAKE = Regex("^[A-Z0-9_]+$")
        private fun hex2(n: Int) = n.toString(16).uppercase().padStart(2, '0')

        internal fun encodeMapKeySegment(raw: String, cfg: Config): String {
            if (!cfg.escapeMapKeys) return raw
            val sep = cfg.separator
            val s = raw
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' -> {
                        out.append("%25")
                        i++
                    }
                    c == '_' -> {
                        out.append("%5F")
                        i++
                    }
                    s.regionMatches(i, sep, 0, sep.length) -> {
                        repeat(sep.length) { j -> out.append('%').append(hex2(sep[j].code)) }
                        i += sep.length
                    }
                    c.isLetterOrDigit() || c == '-' || c == '.' || c == '/' || c == '+' || c == ':' -> {
                        out.append(c)
                        i++
                    }
                    else -> {
                        out.append('%').append(hex2(c.code))
                        i++
                    }
                }
            }
            return out.toString()
        }

        internal fun decodeMapKeySegment(enc: String, cfg: Config): String {
            if (!cfg.escapeMapKeys) return enc
            val s = enc
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    require(i + 2 < s.length) { "Invalid percent escape in map key segment: '$s'" }
                    val hex = s.substring(i + 1, i + 3)
                    val ch = hex.toInt(16).toChar()
                    out.append(ch)
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        internal fun toEnvToken(raw: String, cfg: Config): String =
            if (cfg.honorPretransformedNames && SCREAMING_SNAKE.matches(raw)) raw
            else cfg.nameTransform(raw)

        internal fun defaultToEnvToken(name: String): String =
            name.replace(Regex("([a-z\\d])([A-Z])"), "$1_$2").uppercase()
    }
}

// -------- Decoder --------

private class ConstLeafDecoder(
    private val value: String,
    private val tagDesc: String, // used in error messages
    private val cfg: Env.Config,
    override val serializersModule: SerializersModule,
) : Decoder {
    override fun decodeString(): String = value

    override fun decodeBoolean(): Boolean =
        when (val s = value.trim().lowercase()) {
            "true",
            "1",
            "yes",
            "y",
            "on" -> true
            "false",
            "0",
            "no",
            "n",
            "off" -> false
            else -> throw SerializationException("Invalid boolean for '$tagDesc': '$s'")
        }

    override fun decodeByte(): Byte =
        value.toByteOrNull()
            ?: throw SerializationException("Invalid byte for '$tagDesc': '$value'")

    override fun decodeShort(): Short =
        value.toShortOrNull()
            ?: throw SerializationException("Invalid short for '$tagDesc': '$value'")

    override fun decodeInt(): Int =
        value.toIntOrNull() ?: throw SerializationException("Invalid int for '$tagDesc': '$value'")

    override fun decodeLong(): Long =
        value.toLongOrNull()
            ?: throw SerializationException("Invalid long for '$tagDesc': '$value'")

    override fun decodeFloat(): Float =
        value.toFloatOrNull()
            ?: throw SerializationException("Invalid float for '$tagDesc': '$value'")

    override fun decodeDouble(): Double =
        value.toDoubleOrNull()
            ?: throw SerializationException("Invalid double for '$tagDesc': '$value'")

    override fun decodeChar(): Char =
        value.singleOrNull()
            ?: throw SerializationException("Invalid char for '$tagDesc': '$value'")

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        value.toIntOrNull()?.let { ord ->
            if (ord in 0 until enumDescriptor.elementsCount) return ord
        }
        val idx =
            (0 until enumDescriptor.elementsCount).firstOrNull {
                enumDescriptor.getElementName(it).equals(value, ignoreCase = true)
            }
        return idx
            ?: throw SerializationException(
                "Invalid enum for '$tagDesc': '$value'. Expected one of: ${
                (0 until enumDescriptor.elementsCount).joinToString { enumDescriptor.getElementName(it) }
            }"
            )
    }

    override fun decodeNull(): Nothing? = null

    override fun decodeNotNullMark(): Boolean = true

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

    // Composite not used for map keys
    override fun beginStructure(descriptor: SerialDescriptor) =
        throw SerializationException("Map keys must be primitives or enums, got ${descriptor.kind}")
}

private class EnvDecoder(
    private val env: Map<String, String>,
    private val basePath: String, // prefix for fields at this level
    private val cfg: Env.Config,
    override val serializersModule: SerializersModule,
    private val containerKind: SerialKind, // STRUCTURE kind at this level (CLASS/LIST/…)
    private val singleTag: String? = null, // if non-null, decode leaf value from this exact key
) : Decoder, CompositeDecoder {
    private var cursor = 0

    private var mapEntryKeys: List<String>? = null // encoded segments (escaped)
    private var mapCursor: Int = 0

    private fun entryBase(encKey: String): String =
        if (basePath.isEmpty()) encKey else basePath + cfg.separator + encKey

    private fun ensureMapKeys(): List<String> {
        mapEntryKeys?.let {
            return it
        }
        require(containerKind == StructureKind.MAP) { "ensureMapKeys called for non-MAP" }
        val prefix = if (basePath.isEmpty()) "" else basePath + cfg.separator
        val keys = LinkedHashSet<String>()
        for (k in env.keys) {
            if (!k.startsWith(prefix)) continue
            val tail = k.substring(prefix.length)
            if (tail.isEmpty()) continue
            // The encoded map key ends before the next separator, OR (if there is none)
            // before the first '_' (list index), because '_' is escaped in encoded keys.
            val sepIdx = tail.indexOf(cfg.separator)
            val usIdx = tail.indexOf('_') // safe because '_' can't appear in encoded keys
            val cut =
                when {
                    sepIdx >= 0 && usIdx >= 0 -> minOf(sepIdx, usIdx)
                    sepIdx >= 0 -> sepIdx
                    usIdx >= 0 -> usIdx
                    else -> tail.length
                }
            val encKey = tail.substring(0, cut)
            if (encKey.isNotEmpty()) keys.add(encKey)
        }
        return keys.toList().also { mapEntryKeys = it }
    }

    private fun invalid(type: String, tag: String?, raw: String): Nothing =
        throw SerializationException("Invalid $type for '${tag ?: "value"}': '$raw'")

    private fun String.toByteOrThrow(tag: String?) = toByteOrNull() ?: invalid("byte", tag, this)

    private fun String.toShortOrThrow(tag: String?) = toShortOrNull() ?: invalid("short", tag, this)

    private fun String.toIntOrThrow(tag: String?) = toIntOrNull() ?: invalid("int", tag, this)

    private fun String.toLongOrThrow(tag: String?) = toLongOrNull() ?: invalid("long", tag, this)

    private fun String.toFloatOrThrow(tag: String?) = toFloatOrNull() ?: invalid("float", tag, this)

    private fun String.toDoubleOrThrow(tag: String?) =
        toDoubleOrNull() ?: invalid("double", tag, this)

    // Objects (unordered) => index-by-index probing. Arrays => sequential.
    override fun decodeSequentially(): Boolean =
        containerKind == StructureKind.LIST || containerKind == StructureKind.MAP

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        EnvDecoder(
            env = env,
            basePath = basePath,
            cfg = cfg,
            serializersModule = serializersModule,
            containerKind = descriptor.kind,
            singleTag = singleTag,
        )

    override fun endStructure(descriptor: SerialDescriptor) {}

    // ----- element/key tagging -----

    private fun tokenOf(descriptor: SerialDescriptor, index: Int): String {
        val raw = descriptor.getElementName(index)
        return Env.toEnvToken(raw, cfg)
    }

    private fun qualifyObjectChild(childToken: String): String =
        if (basePath.isEmpty()) childToken else basePath + cfg.separator + childToken

    private fun listItemTag(index: Int): String = basePath + cfg.separator + index

    private fun tagFor(descriptor: SerialDescriptor, index: Int): String =
        when (containerKind) {
            StructureKind.LIST -> listItemTag(index)
            else -> qualifyObjectChild(tokenOf(descriptor, index))
        }

    private fun existsUnder(tag: String): Boolean {
        val sep = cfg.separator
        // Present if exact key, a COUNT key, or any child under TAG__
        return env.containsKey(tag) ||
            env.containsKey(tag + sep + cfg.listCountSuffix.removePrefix("_")) ||
            env.keys.any { it.startsWith("$tag$sep") }
    }

    private fun present(descriptor: SerialDescriptor, index: Int): Boolean {
        val elemKind = descriptor.getElementDescriptor(index).kind
        val tag = tagFor(descriptor, index)
        val sep = cfg.separator
        return when (elemKind) {
            is PrimitiveKind,
            SerialKind.ENUM -> env.containsKey(tag)

            StructureKind.LIST -> {
                // ASP.NET-only: TAG__COUNT or TAG__0 (and nested TAG__0__FIELD)
                env.containsKey(tag + sep + cfg.listCountSuffix.removePrefix("_")) ||
                    env.containsKey("$tag${sep}0") ||
                    env.keys.any { it.startsWith("$tag${sep}0$sep") }
            }

            StructureKind.MAP -> {
                // Key-as-path: any key that starts with TAG__
                env.keys.any { it.startsWith("$tag$sep") }
            }

            else -> existsUnder(tag)
        }
    }

    // ----- element iteration for objects -----

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (decodeSequentially()) {
            return if (cursor < descriptor.elementsCount) cursor++ else CompositeDecoder.DECODE_DONE
        }
        while (cursor < descriptor.elementsCount) {
            val idx = cursor++
            if (present(descriptor, idx)) return idx
        }
        return CompositeDecoder.DECODE_DONE
    }

    // ----- collections -----

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return when (containerKind) {
            StructureKind.LIST -> {
                val sep = cfg.separator
                env[basePath + sep + cfg.listCountSuffix.removePrefix("_")]?.toIntOrNull()?.let {
                    return it
                }
                var i = 0
                while (
                    env.containsKey(listItemTag(i)) ||
                        env.keys.any { it.startsWith(listItemTag(i) + sep) }
                ) {
                    i++
                }
                i
            }
            StructureKind.MAP -> ensureMapKeys().size
            else -> error("decodeCollectionSize only valid for lists/maps")
        }
    }

    // ----- leaf reads (Decoder) -----

    private fun readLeaf(): String {
        val k = singleTag ?: error("Leaf read requires singleTag")
        return env[k] ?: throw SerializationException("Missing env: '$k'")
    }

    override fun decodeString(): String = readLeaf()

    override fun decodeBoolean(): Boolean =
        when (val s = readLeaf().trim().lowercase()) {
            "true",
            "1",
            "yes",
            "y",
            "on" -> true
            "false",
            "0",
            "no",
            "n",
            "off" -> false
            else -> throw SerializationException("Invalid boolean for '$singleTag': '$s'")
        }

    override fun decodeByte(): Byte = readLeaf().toByteOrThrow(singleTag)

    override fun decodeShort(): Short = readLeaf().toShortOrThrow(singleTag)

    override fun decodeInt(): Int = readLeaf().toIntOrThrow(singleTag)

    override fun decodeLong(): Long = readLeaf().toLongOrThrow(singleTag)

    override fun decodeFloat(): Float = readLeaf().toFloatOrThrow(singleTag)

    override fun decodeDouble(): Double = readLeaf().toDoubleOrThrow(singleTag)

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val raw = readLeaf()
        raw.toIntOrNull()?.let { ord ->
            if (ord in 0 until enumDescriptor.elementsCount) return ord
        }
        val nameIdx =
            (0 until enumDescriptor.elementsCount).firstOrNull {
                enumDescriptor.getElementName(it).equals(raw, ignoreCase = true)
            }
        return nameIdx
            ?: throw SerializationException(
                "Invalid enum for '$singleTag': '$raw'. Expected one of: ${ (0 until enumDescriptor.elementsCount).joinToString {
                enumDescriptor
                    .getElementName(
                        it
                    )
            } }"
            )
    }

    override fun decodeChar(): Char =
        readLeaf().singleOrNull()
            ?: throw SerializationException("Invalid char for '$singleTag': '${readLeaf()}'")

    // Nullability at the Decoder level (top-level value, inline, or contextual)
    override fun decodeNotNullMark(): Boolean {
        singleTag?.let { tag ->
            val sep = cfg.separator
            return env.containsKey(tag) ||
                env.containsKey(tag + sep + cfg.listCountSuffix.removePrefix("_")) ||
                env.keys.any { it.startsWith("$tag$sep") }
        }
        return true
    }

    override fun decodeNull(): Nothing? = null

    // Inline (value classes)
    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        // Use the exact key associated with this node: prefer singleTag, otherwise basePath.
        val tag = singleTag ?: basePath
        return EnvDecoder(env, basePath, cfg, serializersModule, descriptor.kind, singleTag = tag)
    }

    // ----- element reads (CompositeDecoder) -----

    // For primitives in objects/lists
    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        env[tagFor(descriptor, index)]
            ?: throw SerializationException("Missing env: '${tagFor(descriptor, index)}'")

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        EnvDecoder(
                env,
                tagFor(descriptor, index),
                cfg,
                serializersModule,
                containerKind,
                singleTag = tagFor(descriptor, index),
            )
            .decodeBoolean()

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toByteOrThrow(tag)
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toShortOrThrow(tag)
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toIntOrThrow(tag)
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toLongOrThrow(tag)
    }

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toFloatOrThrow(tag)
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        val tag = tagFor(descriptor, index)
        return (env[tag] ?: throw SerializationException("Missing env: '$tag'")).toDoubleOrThrow(
            tag
        )
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        decodeStringElement(descriptor, index).singleOrNull()
            ?: throw SerializationException(
                "Invalid char for '${tagFor(descriptor, index)}': '${decodeStringElement(descriptor, index)}'"
            )

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val tag = tagFor(descriptor, index)
        val childKind = descriptor.getElementDescriptor(index).kind
        return EnvDecoder(
            env,
            basePath = tag,
            cfg = cfg,
            serializersModule = serializersModule,
            containerKind = childKind,
            singleTag = tag,
        )
    }

    // Nullables at element level
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        val tag = tagFor(descriptor, index)
        val childKind = descriptor.getElementDescriptor(index).kind
        val present =
            when (childKind) {
                is PrimitiveKind,
                SerialKind.ENUM -> env.containsKey(tag)
                else -> existsUnder(tag)
            }
        if (!present) return null
        val child =
            EnvDecoder(env, basePath = tag, cfg, serializersModule, childKind, singleTag = tag)
        return deserializer.deserialize(child)
    }

    // Structured / nested
    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T {
        if (containerKind == StructureKind.MAP) {
            val keys = ensureMapKeys()
            // Support both protocols:
            //  - A: index is always 0 (key) or 1 (value), repeated per entry -> use mapCursor as
            // the entry index
            //  - B: index grows 0,1,2,3,... -> even=key, odd=value; entry index = index/2
            val slot = index and 1 // 0 -> key, 1 -> value
            val pairIndex = if (index >= 2) index shr 1 else mapCursor

            require(pairIndex in keys.indices) {
                "Map element index $index (pair=$pairIndex) out of range for '$basePath' with ${keys.size} entries"
            }

            val encKey = keys[pairIndex]
            return if (slot == 0) {
                // KEY
                val rawKey = Env.decodeMapKeySegment(encKey, cfg)
                val kd =
                    ConstLeafDecoder(
                        value = rawKey,
                        tagDesc = entryBase(encKey) + " (map key)",
                        cfg = cfg,
                        serializersModule = serializersModule,
                    )
                deserializer.deserialize(kd)
            } else {
                // VALUE
                val entryBase = entryBase(encKey)
                val childKind = deserializer.descriptor.kind
                val child =
                    EnvDecoder(
                        env,
                        basePath = entryBase,
                        cfg,
                        serializersModule,
                        childKind,
                        singleTag = entryBase,
                    )
                // Only advance the implicit cursor in protocol A (indices 0/1 repeated).
                if (index < 2) mapCursor += 1
                deserializer.deserialize(child)
            }
        } else {
            val tag = tagFor(descriptor, index)
            val childKind = descriptor.getElementDescriptor(index).kind
            val child =
                EnvDecoder(env, basePath = tag, cfg, serializersModule, childKind, singleTag = tag)
            return deserializer.deserialize(child)
        }
    }
}

// -------- Encoder --------

private class KeyStringEncoder(
    private val cfg: Env.Config,
    override val serializersModule: SerializersModule,
) : Encoder {
    var result: String? = null
        private set

    private fun set(v: String) {
        check(result == null) { "Map key encoded twice?" }
        result = v
    }

    override fun encodeString(value: String) = set(value)

    override fun encodeBoolean(value: Boolean) = set(value.toString())

    override fun encodeByte(value: Byte) = set(value.toString())

    override fun encodeShort(value: Short) = set(value.toString())

    override fun encodeInt(value: Int) = set(value.toString())

    override fun encodeLong(value: Long) = set(value.toString())

    override fun encodeFloat(value: Float) = set(value.toString())

    override fun encodeDouble(value: Double) = set(value.toString())

    override fun encodeChar(value: Char) = set(value.toString())

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        val v = if (cfg.enumKeysAsNames) enumDescriptor.getElementName(index) else index.toString()
        set(v)
    }

    override fun encodeNull(): Unit = throw SerializationException("Map keys cannot be null")

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException("Map keys must be primitives or enums, got ${descriptor.kind}")
}

private class EnvEncoder(
    private val out: MutableMap<String, String>,
    private val basePath: String,
    private val cfg: Env.Config,
    override val serializersModule: SerializersModule,
    private val containerKind: SerialKind,
    private val singleTag: String? = null,
) : Encoder, CompositeEncoder {
    private var pendingMapKey: String? = null // encoded (escaped) key segment

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        EnvEncoder(
            out = out,
            basePath = basePath,
            cfg = cfg,
            serializersModule = serializersModule,
            containerKind = descriptor.kind,
            singleTag = singleTag,
        )

    // Lists: size is provided here; emit <TAG>_COUNT if configured.
    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        if (containerKind == StructureKind.LIST && cfg.writeListCount) {
            val countKey =
                basePath + cfg.separator + cfg.listCountSuffix.removePrefix("_") // TAG__COUNT
            out[countKey] = collectionSize.toString()
        }
        // MAP: no COUNT key
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {}

    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean =
        cfg.encodeDefaults // Per-property @EncodeDefault still forces inclusion.

    // :contentReference[oaicite:4]{index=4}

    // Tag helpers
    private fun tokenOf(descriptor: SerialDescriptor, index: Int): String =
        Env.toEnvToken(descriptor.getElementName(index), cfg)

    private fun qualifyObjectChild(childToken: String): String =
        if (basePath.isEmpty()) childToken else basePath + cfg.separator + childToken

    private fun listItemTag(index: Int): String = basePath + cfg.separator + index

    private fun tagFor(descriptor: SerialDescriptor, index: Int): String =
        when (containerKind) {
            StructureKind.LIST -> listItemTag(index)
            else -> qualifyObjectChild(tokenOf(descriptor, index))
        }

    // Leaf writes (Encoder)
    private fun putLeaf(v: String) {
        val k = singleTag ?: error("Leaf write requires singleTag")
        out[k] = v
    }

    override fun encodeNull() {
        /* omit nulls */
    }

    override fun encodeString(value: String) = putLeaf(value)

    override fun encodeBoolean(value: Boolean) = putLeaf(value.toString())

    override fun encodeByte(value: Byte) = putLeaf(value.toString())

    override fun encodeShort(value: Short) = putLeaf(value.toString())

    override fun encodeInt(value: Int) = putLeaf(value.toString())

    override fun encodeLong(value: Long) = putLeaf(value.toString())

    override fun encodeFloat(value: Float) = putLeaf(value.toString())

    override fun encodeDouble(value: Double) = putLeaf(value.toString())

    override fun encodeChar(value: Char) = putLeaf(value.toString())

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        val v = if (cfg.enumsAsNames) enumDescriptor.getElementName(index) else index.toString()
        putLeaf(v)
    }

    // Inline (value classes)
    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        val tag = singleTag ?: basePath
        return EnvEncoder(out, basePath, cfg, serializersModule, descriptor.kind, singleTag = tag)
    }

    // Element writes (CompositeEncoder)
    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        out[tagFor(descriptor, index)] = value
    }

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        out[tagFor(descriptor, index)] = value.toString()
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        val tag = tagFor(descriptor, index)
        val childKind = descriptor.getElementDescriptor(index).kind
        return EnvEncoder(
            out,
            basePath = tag,
            cfg = cfg,
            serializersModule = serializersModule,
            containerKind = childKind,
            singleTag = tag,
        )
    }

    @ExperimentalSerializationApi
    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        if (value == null) return // omit
        // If a format needs explicit nulls, it would call encodeNull/encodeNotNullMark here.
        encodeSerializableElement(descriptor, index, serializer, value)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (containerKind == StructureKind.MAP) {
            when (index) {
                0 -> { // capture key
                    val kenc = KeyStringEncoder(cfg, serializersModule)
                    @Suppress("UNCHECKED_CAST")
                    (serializer as SerializationStrategy<Any?>).serialize(kenc, value)
                    val rawKey =
                        kenc.result
                            ?: throw SerializationException("Map key did not produce a value")
                    pendingMapKey = Env.encodeMapKeySegment(rawKey, cfg)
                    return
                }
                1 -> { // write value under basePath__<encodedKey>
                    val encKey =
                        pendingMapKey
                            ?: throw SerializationException(
                                "Map value encoded without a preceding key"
                            )
                    val entryBase =
                        if (basePath.isEmpty()) encKey else basePath + cfg.separator + encKey
                    val childKind = serializer.descriptor.kind
                    val child =
                        EnvEncoder(
                            out,
                            basePath = entryBase,
                            cfg = cfg,
                            serializersModule = serializersModule,
                            containerKind = childKind,
                            singleTag = entryBase,
                        )
                    @Suppress("UNCHECKED_CAST")
                    (serializer as SerializationStrategy<Any?>).serialize(child, value)
                    pendingMapKey = null
                    return
                }
                else -> error("Map element index must be 0 (key) or 1 (value)")
            }
        } else {
            val tag = tagFor(descriptor, index)
            val childKind = descriptor.getElementDescriptor(index).kind
            val child = EnvEncoder(out, tag, cfg, serializersModule, childKind, singleTag = tag)
            serializer.serialize(child, value)
        }
    }
}
