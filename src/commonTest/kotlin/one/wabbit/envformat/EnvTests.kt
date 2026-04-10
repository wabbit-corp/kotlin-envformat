@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package one.wabbit.envformat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.jvm.JvmInline
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

// ----- Test fixtures -----

@Serializable
data class PasswordPolicyT(
    val minLength: Int = 10,
    val requireUppercase: Boolean = true,
    val requireLowercase: Boolean = true,
    val requireDigit: Boolean = true,
    val requireSpecial: Boolean = true,
    val isAsciiOnly: Boolean = true,
)

@Serializable data class VendorHeaderT(val name: String, val value: String)

@JvmInline @Serializable value class CidrT(val value: String)

@Serializable
enum class ModeT {
    OFF,
    ON,
    AUTO,
}

@Serializable
data class IdentityConfigT(
    val globalRateLimitCapacityPerIp: Int = 60,
    val globalRateLimitRefillPerSecondPerIp: Int = 30,
    val serverAuthChallengeTtlSec: Long = 60,
    val argonMemoryKb: Int = 65536,
    val argonIterations: Int = 3,
    val argonParallelism: Int = 1,
    val argonWorkerThreads: Int = 8,
    val argonMaxInFlight: Int = 16,
    val maxEmailLen: Int = 254,
    val maxUaLen: Int = 512,
    val dbBusyTimeoutMs: Int = 5000,
    val accessTtlSeconds: Long = 3600L,
    val refreshTtlSeconds: Long = 86400L,
    val rotationOverlapSeconds: Long = 60L,
    val totpSecretTtlSeconds: Long = 10 * 60L,
    val serverListOnlineWindowSeconds: Long = 5 * 60L,
    val clockSkewSeconds: Long = 2L,
    val joinProofTtlSec: Long = 30,
    val presenceCertTtlSec: Long = 900,
    val trustedProxyCidrs: List<CidrT> = listOf(),
    val vendorHeaders: List<VendorHeaderT> = listOf(),
    val passwordPolicy: PasswordPolicyT = PasswordPolicyT(),
)

@Serializable
data class PrimitivesBox(
    val i: Int,
    val l: Long,
    val f: Float,
    val d: Double,
    val b: Boolean,
    val c: Char,
    val s: String,
)

@Serializable
data class NullableBox(val a: Int? = null, val b: String? = null, val c: CidrT? = null)

@Serializable data class EnumBox(val m: ModeT = ModeT.AUTO)

@Serializable data class ListBox(val xs: List<Int> = emptyList())

@Serializable data class ListOfInlineBox(val cidrs: List<CidrT> = emptyList())

@Serializable data class ListOfObjectsBox(val headers: List<VendorHeaderT> = emptyList())

@Serializable data class InlineHolder(val cidr: CidrT)

@Serializable
data class EncodeDefaultsBox(
    val a: Int = 42,
    val b: Int = 7,
    val list: List<Int> = listOf(1, 2),
    val nested: Nested = Nested(),
) {
    @Serializable data class Nested(val x: Int = 5, val y: Int = 6)
}

@Serializable
data class WithEncodeDefault(@EncodeDefault val always: Int = 123, val regular: Int = 456)

@Serializable
data class SerialNameBox(
    @kotlinx.serialization.SerialName("ALREADY_GOOD") val snake: Int = 1,
    @kotlinx.serialization.SerialName("lower_snake") val lowerSnake: Int = 2,
)

// ----- Utilities -----

private inline fun <R> withEnvConfig(tweak: (Env.Config) -> Env.Config, block: (Env) -> R): R =
    block(Env(config = tweak(Env.Config())))

private fun String.kv(v: Any) = this to v.toString()

// ----- Tests -----

class EnvFormatTest {
    // --- Decoding ---

    @Test
    fun decode_primitives_with_prefix() {
        val env =
            mapOf(
                "P__I".kv(1),
                "P__L".kv(2L),
                "P__F".kv(3.5f),
                "P__D".kv(4.25),
                "P__B".kv("on"), // boolean synonyms
                "P__C".kv("Z"),
                "P__S".kv("hello"),
            )
        val got = Env.decode<PrimitivesBox>("P", env)
        assertEquals(1, got.i)
        assertEquals(2L, got.l)
        assertEquals(3.5f, got.f)
        assertEquals(4.25, got.d)
        assertEquals(true, got.b)
        assertEquals('Z', got.c)
        assertEquals("hello", got.s)
    }

    @Test
    fun decode_map() {
        @Serializable data class M(val m: Map<String, Int>)
        val env = mapOf("P__M__foo" to "1", "P__M__bar" to "2")
        assertEquals(mapOf("foo" to 1, "bar" to 2), Env.decode<M>("P", env).m)
    }

    @Test
    fun decode_map1() {
        @Serializable data class M2(val m: Map<String, List<Int>>)
        val env = mapOf("P__M__k__COUNT" to "2", "P__M__k__0" to "10", "P__M__k__1" to "20")
        assertEquals(mapOf("k" to listOf(10, 20)), Env.decode<M2>("P", env).m)
    }

    @Test
    fun decode_identity_like_nested_and_defaults() {
        val env =
            mapOf(
                "IDENTITY__GLOBAL_RATE_LIMIT_CAPACITY_PER_IP".kv(120),
                "IDENTITY__PASSWORD_POLICY__MIN_LENGTH".kv(12),
                "IDENTITY__TRUSTED_PROXY_CIDRS__COUNT".kv(2),
                "IDENTITY__TRUSTED_PROXY_CIDRS__0".kv("10.0.0.0/8"),
                "IDENTITY__TRUSTED_PROXY_CIDRS__1".kv("192.168.0.0/16"),
            )
        val cfg = Env.decode<IdentityConfigT>("IDENTITY", env)
        assertEquals(120, cfg.globalRateLimitCapacityPerIp)
        assertEquals(12, cfg.passwordPolicy.minLength)
        assertEquals(2, cfg.trustedProxyCidrs.size)
        assertEquals(CidrT("10.0.0.0/8"), cfg.trustedProxyCidrs[0])
        assertEquals(CidrT("192.168.0.0/16"), cfg.trustedProxyCidrs[1])
        // untouched fields use defaults
        assertEquals(30, cfg.globalRateLimitRefillPerSecondPerIp)
        assertEquals(512, cfg.maxUaLen)
    }

    @Test
    fun decode_list_without_count_contiguous_only() {
        val env =
            mapOf(
                "P__XS__0".kv(10),
                "P__XS__1".kv(20),
                // hole at _2
                "P__XS__3"
                    .kv(999), // should be ignored because we stop at first gap when COUNT absent
            )
        val box = Env.decode<ListBox>("P", env)
        assertEquals(listOf(10, 20), box.xs)
    }

    @Test
    fun decode_list_with_count_requires_elements() {
        val env =
            mapOf(
                "P__XS__COUNT".kv(3),
                "P__XS__0".kv(1),
                "P__XS__1".kv(2),
                // missing _2 -> should throw when trying to read element 2
            )
        val ex = assertFailsWith<SerializationException> { Env.decode<ListBox>("P", env) }
        assertTrue(ex.message!!.contains("P__XS__2"))
    }

    @Test
    fun decode_list_of_objects_nested_fields() {
        val env =
            mapOf(
                "P__HEADERS__COUNT".kv(2),
                "P__HEADERS__0__NAME".kv("X"),
                "P__HEADERS__0__VALUE".kv("1"),
                "P__HEADERS__1__NAME".kv("Y"),
                "P__HEADERS__1__VALUE".kv("2"),
            )
        val got = Env.decode<ListOfObjectsBox>("P", env)
        assertEquals(2, got.headers.size)
        assertEquals(VendorHeaderT("X", "1"), got.headers[0])
        assertEquals(VendorHeaderT("Y", "2"), got.headers[1])
    }

    @Test
    fun decode_inline_value_class_property_and_list() {
        val env1 = mapOf("P__CIDR".kv("10.0.0.0/8"))
        val got1 = Env.decode<InlineHolder>("P", env1)
        assertEquals(CidrT("10.0.0.0/8"), got1.cidr)

        val env2 =
            mapOf(
                "P__CIDRS__COUNT".kv(2),
                "P__CIDRS__0".kv("10.0.0.0/8"),
                "P__CIDRS__1".kv("192.168.0.0/16"),
            )
        val got2 = Env.decode<ListOfInlineBox>("P", env2)
        assertEquals(listOf(CidrT("10.0.0.0/8"), CidrT("192.168.0.0/16")), got2.cidrs)
    }

    @Test
    fun decode_nullables_absent_to_null() {
        val env = emptyMap<String, String>()
        val got = Env.decode<NullableBox>("P", env)
        assertNull(got.a)
        assertNull(got.b)
        assertNull(got.c)
    }

    @Test
    fun decode_enum_by_name_or_ordinal() {
        val byName = Env.decode<EnumBox>("P", mapOf("P__M".kv("on")))
        assertEquals(ModeT.ON, byName.m)

        val byOrdinal = Env.decode<EnumBox>("P", mapOf("P__M".kv(2))) // AUTO
        assertEquals(ModeT.AUTO, byOrdinal.m)
    }

    @Test
    fun decode_missing_required_field_throws() {
        @Serializable data class Required(val count: Int)
        val env = emptyMap<String, String>()
        val ex = assertFailsWith<SerializationException> { Env.decode<Required>("P", env) }
        assertTrue(ex.message!!.contains("count"))
    }

    @Test
    fun decode_invalid_boolean_throws_with_key() {
        @Serializable data class B(val flag: Boolean)
        val ex =
            assertFailsWith<SerializationException> {
                Env.decode<B>("P", mapOf("P__FLAG" to "perhaps"))
            }
        assertTrue(ex.message!!.contains("Invalid boolean"))
        assertTrue(ex.message!!.contains("P__FLAG"))
    }

    @Test
    fun decode_char_happy_and_sad() {
        @Serializable data class CB(val c: Char)
        val ok = Env.decode<CB>("P", mapOf("P__C" to "A"))
        assertEquals('A', ok.c)
        val ex =
            assertFailsWith<SerializationException> { Env.decode<CB>("P", mapOf("P__C" to "AB")) }
        assertTrue(ex.message!!.contains("Invalid char"))
    }

    @Test
    fun decode_serial_name_bypass_and_transform() {
        val env =
            mapOf(
                "P__ALREADY_GOOD".kv(5),
                "P__LOWER_SNAKE".kv(9), // "lower_snake" transformed to SCREAMING_SNAKE
            )
        val got = Env.decode<SerialNameBox>("P", env)
        assertEquals(5, got.snake)
        assertEquals(9, got.lowerSnake)
    }

    // --- Encoding ---

    @Test
    fun encode_respects_encodeDefaults_false() {
        val box =
            EncodeDefaultsBox(
                a = 42, // default
                b = 99, // changed
                list = listOf(3, 4), // changed
                nested = EncodeDefaultsBox.Nested(x = 5, y = 123), // partial change
            )
        val out = Env.encodeToMap(box, prefix = "P", encodeDefaults = false)
        // Should include only changed fields (and list metadata)
        assertTrue("P__B" in out)
        assertEquals("99", out["P__B"])
        assertEquals("2", out["P__LIST__COUNT"])
        assertEquals("3", out["P__LIST__0"])
        assertEquals("4", out["P__LIST__1"])
        // Nested: only changed y expected under nested object
        assertEquals("123", out["P__NESTED__Y"])
        // Defaults omitted:
        assertFalse("P__A" in out) // default value
        assertFalse("P__NESTED__X" in out) // default value
    }

    @Test
    fun encode_respects_encodeDefaults_true() {
        val box = EncodeDefaultsBox() // all defaults
        val out = Env.encodeToMap(box, prefix = "P", encodeDefaults = true)
        // spot-check a few defaults are present:
        assertEquals("42", out["P__A"])
        assertEquals("7", out["P__B"])
        // list with defaults appears fully
        assertEquals("2", out["P__LIST__COUNT"])
        assertEquals("1", out["P__LIST__0"])
        assertEquals("2", out["P__LIST__1"])
        // nested defaults appear
        assertEquals("5", out["P__NESTED__X"])
        assertEquals("6", out["P__NESTED__Y"])
    }

    @Test
    fun encode_property_annotated_with_EncodeDefault_is_included_even_when_disabled() {
        val out = Env.encodeToMap(WithEncodeDefault(), prefix = "P", encodeDefaults = false)
        assertEquals("123", out["P__ALWAYS"])
        // regular default omitted
        assertFalse("P__REGULAR" in out)
    }

    @Test
    fun encode_list_count_toggle() {
        val box = ListBox(xs = listOf(10, 20))
        // writeListCount = false -> omit COUNT
        withEnvConfig({ it.copy(writeListCount = false) }) { env ->
            val out = env.encodeToMap(box, prefix = "P", encodeDefaults = false)
            assertFalse("P__XS_COUNT" in out)
            assertEquals("10", out["P__XS__0"])
            assertEquals("20", out["P__XS__1"])
        }
        // writeListCount = true -> include COUNT
        withEnvConfig({ it.copy(writeListCount = true) }) { env ->
            val out = env.encodeToMap(box, prefix = "P", encodeDefaults = false)
            assertEquals("2", out["P__XS__COUNT"])
            assertEquals("10", out["P__XS__0"])
            assertEquals("20", out["P__XS__1"])
        }
    }

    @Test
    fun encode_inline_value_class_property_and_list() {
        val one = InlineHolder(CidrT("10.0.0.0/8"))
        val m1 = Env.encodeToMap(one, prefix = "P", encodeDefaults = false)
        assertEquals("10.0.0.0/8", m1["P__CIDR"])

        val two = ListOfInlineBox(listOf(CidrT("10.0.0.0/8"), CidrT("192.168.0.0/16")))
        val m2 = Env.encodeToMap(two, prefix = "P", encodeDefaults = false)
        assertEquals("2", m2["P__CIDRS__COUNT"])
        assertEquals("10.0.0.0/8", m2["P__CIDRS__0"])
        assertEquals("192.168.0.0/16", m2["P__CIDRS__1"])
    }

    @Test
    fun roundtrip_encode_then_decode_identity_subset() {
        val cfg =
            IdentityConfigT(
                globalRateLimitCapacityPerIp = 111,
                trustedProxyCidrs = listOf(CidrT("10.0.0.0/8")),
                passwordPolicy = PasswordPolicyT(minLength = 12, requireDigit = false),
            )
        val out = Env.encodeToMap(cfg, prefix = "IDENTITY", encodeDefaults = false)
        // sanity: contains changed fields
        assertEquals("111", out["IDENTITY__GLOBAL_RATE_LIMIT_CAPACITY_PER_IP"])
        assertEquals("1", out["IDENTITY__TRUSTED_PROXY_CIDRS__COUNT"])
        assertEquals("10.0.0.0/8", out["IDENTITY__TRUSTED_PROXY_CIDRS__0"])
        assertEquals("12", out["IDENTITY__PASSWORD_POLICY__MIN_LENGTH"])
        assertEquals("false", out["IDENTITY__PASSWORD_POLICY__REQUIRE_DIGIT"])
        // decode back
        val back = Env.decode<IdentityConfigT>("IDENTITY", out)
        assertEquals(cfg, back)
    }

    @Test
    fun encode_with_empty_prefix_writes_bare_keys() {
        val box = PrimitivesBox(1, 2, 3f, 4.0, true, 'Q', "s")
        val out = Env.encodeToMap(box, prefix = "", encodeDefaults = false)
        assertTrue("I" in out)
        assertTrue("L" in out)
        assertTrue("B" in out)
        assertEquals("Q", out["C"])
        val back = Env.decode<PrimitivesBox>(prefix = "", env = out)
        assertEquals(box, back)
    }
}
