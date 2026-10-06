package com.sleepsaver.core.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.domain.insights.InsightsEngine
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.OptionalInt
import java.util.stream.IntStream
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test

/**
 * core:domain is plain Kotlin/JVM built against a desktop JDK, so Android lint never looks at it, yet it runs on
 * devices down to minSdk 28. A JDK method Android only added later compiles fine here and then throws
 * NoSuchMethodError on older phones.
 *
 * This test reads the constant pool of every main class of the module and fails on references to the APIs in
 * [NEWER_THAN_MIN_SDK], matched by owner, name and (where it matters) descriptor, so Java 8 overloads such as
 * Duration.dividedBy(long) or Instant.truncatedTo stay allowed. The list covers the usual traps: Java 9+ java.time
 * additions, Java 9+ collection factories, stream and Optional additions (the Int/Long/Double ones too), String /
 * Math overloads, Stream.toList(), and the Java 21
 * SequencedCollection members, which Kotlin picks over its own removeFirst()/removeLast()/reversed() extensions
 * when the compile JDK has them. The API level shown is when Android added the method; anything above 28 fails.
 */
class MinSdkApiTest {

    @Test
    fun `every main class of core domain only uses APIs available on API 28`() {
        val classes = mainClasses()
        assertThat(classes.keys.map { it.substringBeforeLast('/') }.toSet()).containsAtLeast(
            "$PACKAGE/circadian", "$PACKAGE/config", "$PACKAGE/debt", "$PACKAGE/energy", "$PACKAGE/habits",
            "$PACKAGE/insights", "$PACKAGE/model", "$PACKAGE/need", "$PACKAGE/night",
        )

        val violations = classes.flatMap { (name, bytes) ->
            references(bytes).mapNotNull { ref -> newerApi(ref)?.let { api -> "$name uses $ref (API $api)" } }
        }

        assertWithMessage("APIs newer than minSdk 28 (use a Java 8 equivalent, e.g. instant.atZone(zone).toLocalDate())")
            .that(violations).isEmpty()
    }

    @Test
    fun `the scanner reads member references from the constant pool`() {
        val references = mainClasses().values.flatMap(::references).toSet()

        assertThat(references).contains(
            Ref("java/time/Duration", "between", "(Ljava/time/temporal/Temporal;Ljava/time/temporal/Temporal;)Ljava/time/Duration;"),
        )
        assertThat(references).contains(Ref("java/time/ZonedDateTime", "toLocalDate", "()Ljava/time/LocalDate;"))
    }

    @Test
    fun `the scanner flags newer APIs compiled by this toolchain`() {
        val flagged = references(classBytes(NewerApiCanary::class.java)).filter { newerApi(it) != null }

        assertThat(flagged.map { "${it.owner}.${it.name}" }).containsAtLeast(
            "java/time/LocalDate.ofInstant",
            "java/time/LocalTime.ofInstant",
            "java/time/Duration.toSeconds",
            "java/time/Duration.toMinutesPart",
            "java/time/Duration.dividedBy",
            "java/time/Duration.truncatedTo",
            "java/time/LocalDate.datesUntil",
            "java/util/stream/Stream.toList",
            "java/lang/Math.floorMod",
            "java/util/stream/IntStream.takeWhile",
            "java/util/OptionalInt.orElseThrow",
        )
    }

    @Test
    fun `the scanner leaves the Java 8 overloads alone`() {
        val flagged = references(classBytes(AllowedApiCanary::class.java)).filter { newerApi(it) != null }

        assertThat(flagged).isEmpty()
    }

    // ------------------------------------------------------------------ class files

    /** Internal class name → bytes, for every class of core:domain's main output (a directory or a jar). */
    private fun mainClasses(): Map<String, ByteArray> {
        val location = Paths.get(InsightsEngine::class.java.protectionDomain.codeSource.location.toURI())
        val classes = if (Files.isDirectory(location)) {
            Files.walk(location).use { paths ->
                paths.iterator().asSequence()
                    .filter { it.toString().endsWith(".class") }
                    .associate { classNameOf(location.relativize(it)) to Files.readAllBytes(it) }
            }
        } else {
            ZipFile(location.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .associate { it.name.removeSuffix(".class") to jar.getInputStream(it).use { input -> input.readBytes() } }
            }
        }
        return classes.filterKeys { it.startsWith("$PACKAGE/") }
            .also { assertWithMessage("no main classes found under $location").that(it).isNotEmpty() }
    }

    private fun classNameOf(relative: Path): String = relative.joinToString("/").removeSuffix(".class")

    private fun classBytes(type: Class<*>): ByteArray =
        checkNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")) { "no class file for $type" }
            .use { it.readBytes() }

    private data class Ref(val owner: String, val name: String, val descriptor: String) {
        override fun toString() = "$owner.$name$descriptor"
    }

    /** Field, method and interface-method references in a class file's constant pool (JVMS §4.4). */
    private fun references(classFile: ByteArray): List<Ref> {
        val input = DataInputStream(ByteArrayInputStream(classFile))
        check(input.readInt() == CLASS_MAGIC) { "not a class file" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classNameIndex = IntArray(count)
        val nameIndex = IntArray(count)
        val descriptorIndex = IntArray(count)
        val memberRefs = ArrayList<Pair<Int, Int>>() // (class index, name-and-type index)
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                TAG_UTF8 -> utf8[i] = input.readUTF()
                TAG_INTEGER, TAG_FLOAT -> input.skipFully(4)
                TAG_LONG, TAG_DOUBLE -> {
                    input.skipFully(8)
                    i++ // takes two slots
                }
                TAG_CLASS -> classNameIndex[i] = input.readUnsignedShort()
                TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> input.skipFully(2)
                TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF ->
                    memberRefs += input.readUnsignedShort() to input.readUnsignedShort()
                TAG_NAME_AND_TYPE -> {
                    nameIndex[i] = input.readUnsignedShort()
                    descriptorIndex[i] = input.readUnsignedShort()
                }
                TAG_METHOD_HANDLE -> input.skipFully(3)
                TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> input.skipFully(4)
                else -> error("unknown constant pool tag $tag at #$i")
            }
            i++
        }
        val classRefs = classNameIndex.indices.filter { classNameIndex[it] != 0 }
            .map { Ref(checkNotNull(utf8[classNameIndex[it]]), "", "") }
        return memberRefs.map { (classIndex, nameAndType) ->
            Ref(
                owner = checkNotNull(utf8[classNameIndex[classIndex]]),
                name = checkNotNull(utf8[nameIndex[nameAndType]]),
                descriptor = checkNotNull(utf8[descriptorIndex[nameAndType]]),
            )
        } + classRefs
    }

    private fun DataInputStream.skipFully(bytes: Int) {
        check(skipBytes(bytes) == bytes) { "truncated class file" }
    }

    // ------------------------------------------------------------------ the list

    private class NewerApi(val owners: Set<String>, val name: String?, val descriptor: String?, val api: Int) {
        fun matches(ref: Ref): Boolean =
            ref.owner in owners && (name == null || ref.name == name) && (descriptor == null || ref.descriptor == descriptor)
    }

    private fun newerApi(ref: Ref): Int? = NEWER_THAN_MIN_SDK.firstOrNull { it.matches(ref) }?.api

    private companion object {
        const val PACKAGE = "com/sleepsaver/core/domain"
        val CLASS_MAGIC = 0xCAFEBABE.toInt()
        const val TAG_UTF8 = 1
        const val TAG_INTEGER = 3
        const val TAG_FLOAT = 4
        const val TAG_LONG = 5
        const val TAG_DOUBLE = 6
        const val TAG_CLASS = 7
        const val TAG_STRING = 8
        const val TAG_FIELD_REF = 9
        const val TAG_METHOD_REF = 10
        const val TAG_INTERFACE_METHOD_REF = 11
        const val TAG_NAME_AND_TYPE = 12
        const val TAG_METHOD_HANDLE = 15
        const val TAG_METHOD_TYPE = 16
        const val TAG_DYNAMIC = 17
        const val TAG_INVOKE_DYNAMIC = 18
        const val TAG_MODULE = 19
        const val TAG_PACKAGE = 20

        fun api(owner: String, name: String, api: Int, descriptor: String? = null) =
            NewerApi(setOf(owner), name, descriptor, api)

        fun apis(owners: Set<String>, api: Int, vararg names: String) = names.map { NewerApi(owners, it, null, api) }

        /** A whole class Android added later: any reference to it fails. */
        fun newClass(owner: String, api: Int) = NewerApi(setOf(owner), null, null, api)

        val SEQUENCED_COLLECTIONS = setOf(
            "java/util/SequencedCollection", "java/util/SequencedSet", "java/util/List", "java/util/ArrayList",
            "java/util/LinkedHashSet", "java/util/SortedSet", "java/util/NavigableSet", "java/util/TreeSet",
        )
        val SEQUENCED_MAPS = setOf("java/util/SequencedMap", "java/util/LinkedHashMap")
        val SORTED_MAPS = setOf("java/util/SortedMap", "java/util/NavigableMap", "java/util/TreeMap")
        val DEQUES = setOf("java/util/Deque", "java/util/ArrayDeque", "java/util/LinkedList")
        val MATH = setOf("java/lang/Math", "java/lang/StrictMath")
        val PRIMITIVE_STREAMS = setOf("java/util/stream/IntStream", "java/util/stream/LongStream", "java/util/stream/DoubleStream")
        val PRIMITIVE_OPTIONALS = setOf("java/util/OptionalInt", "java/util/OptionalLong", "java/util/OptionalDouble")

        val NEWER_THAN_MIN_SDK: List<NewerApi> = listOf(
            // java.time (Java 9+)
            api("java/time/Duration", "toSeconds", 31, "()J"),
            api("java/time/Duration", "dividedBy", 31, "(Ljava/time/Duration;)J"),
            api("java/time/Duration", "truncatedTo", 31),
            api("java/time/Duration", "isPositive", 35),
        ) + apis(
            setOf("java/time/Duration"), 31,
            "toDaysPart", "toHoursPart", "toMinutesPart", "toSecondsPart", "toMillisPart", "toNanosPart",
        ) + listOf(
            api("java/time/LocalDate", "ofInstant", 34),
            api("java/time/LocalDate", "datesUntil", 34),
            api("java/time/LocalDate", "toEpochSecond", 34),
            api("java/time/LocalDate", "EPOCH", 34),
            api("java/time/LocalTime", "ofInstant", 31),
            api("java/time/LocalTime", "toEpochSecond", 34),
            api("java/time/OffsetTime", "toEpochSecond", 34),
            api("java/time/Clock", "tickMillis", 34),
            api("java/time/chrono/Chronology", "epochSecond", 34),
        ) + apis(
            // java.util.stream (Java 9+)
            setOf("java/util/stream/Stream"), 33, "takeWhile", "dropWhile", "ofNullable",
        ) + listOf(
            api("java/util/stream/Stream", "toList", 34),
            api("java/util/stream/Stream", "mapMulti", 34),
            api(
                "java/util/stream/Stream", "iterate", 33,
                "(Ljava/lang/Object;Ljava/util/function/Predicate;Ljava/util/function/UnaryOperator;)Ljava/util/stream/Stream;",
            ),
            api(
                "java/util/stream/IntStream", "iterate", 33,
                "(ILjava/util/function/IntPredicate;Ljava/util/function/IntUnaryOperator;)Ljava/util/stream/IntStream;",
            ),
            api(
                "java/util/stream/LongStream", "iterate", 33,
                "(JLjava/util/function/LongPredicate;Ljava/util/function/LongUnaryOperator;)Ljava/util/stream/LongStream;",
            ),
            api(
                "java/util/stream/DoubleStream", "iterate", 33,
                "(DLjava/util/function/DoublePredicate;Ljava/util/function/DoubleUnaryOperator;)Ljava/util/stream/DoubleStream;",
            ),
            api("java/util/stream/Collectors", "teeing", 34),
        ) + apis(
            setOf("java/util/stream/Collectors"), 33,
            "toUnmodifiableList", "toUnmodifiableSet", "toUnmodifiableMap", "filtering", "flatMapping",
        ) + apis(PRIMITIVE_STREAMS, 33, "takeWhile", "dropWhile") + apis(PRIMITIVE_STREAMS, 34, "mapMulti") + apis(
            // Immutable collection factories (Java 9/10)
            setOf("java/util/List", "java/util/Set", "java/util/Map"), 30, "of", "copyOf",
        ) + apis(setOf("java/util/Map"), 30, "ofEntries", "entry") + apis(
            // SequencedCollection / SequencedMap (Java 21)
            SEQUENCED_COLLECTIONS, 35, "getFirst", "getLast", "removeFirst", "removeLast", "addFirst", "addLast", "reversed",
        ) + apis(DEQUES, 35, "reversed") + apis(
            SEQUENCED_MAPS, 35,
            "firstEntry", "lastEntry", "pollFirstEntry", "pollLastEntry", "putFirst", "putLast", "reversed",
            "sequencedKeySet", "sequencedValues", "sequencedEntrySet",
        ) + apis(
            SORTED_MAPS, 35, "putFirst", "putLast", "reversed", "sequencedKeySet", "sequencedValues", "sequencedEntrySet",
        ) + listOf(
            newClass("java/util/SequencedCollection", 35),
            newClass("java/util/SequencedSet", 35),
            newClass("java/util/SequencedMap", 35),
            newClass("java/util/HexFormat", 34),
            newClass("java/lang/Record", 34),
            api("java/util/Collection", "toArray", 33, "(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"),
            api("java/util/Optional", "orElseThrow", 33, "()Ljava/lang/Object;"),
            api("java/util/OptionalInt", "orElseThrow", 33, "()I"),
            api("java/util/OptionalLong", "orElseThrow", 33, "()J"),
            api("java/util/OptionalDouble", "orElseThrow", 33, "()D"),
        ) + apis(setOf("java/util/Optional"), 33, "isEmpty", "stream", "or", "ifPresentOrElse") + apis(
            PRIMITIVE_OPTIONALS, 33, "isEmpty", "stream", "ifPresentOrElse",
        ) + apis(
            setOf("java/util/Objects"), 30,
            "requireNonNullElse", "requireNonNullElseGet", "checkIndex", "checkFromToIndex", "checkFromIndexSize",
        ) + listOf(
            // Math overloads and additions (Java 9+); the (long, long) versions are Java 8 and fine
            api("java/lang/Math", "floorMod", 31, "(JI)I"),
            api("java/lang/StrictMath", "floorMod", 31, "(JI)I"),
            api("java/lang/Math", "floorDiv", 31, "(JI)J"),
            api("java/lang/StrictMath", "floorDiv", 31, "(JI)J"),
            api("java/lang/Math", "multiplyExact", 31, "(JI)J"),
            api("java/lang/StrictMath", "multiplyExact", 31, "(JI)J"),
        ) + apis(
            MATH, 31, "multiplyFull", "multiplyHigh", "fma", "absExact", "ceilDiv", "ceilMod", "ceilDivExact",
            "floorDivExact", "divideExact", "clamp", "unsignedMultiplyHigh",
        ) + apis(
            // String (Java 11+); kotlin.String hides these, but a java.lang.String receiver would not
            setOf("java/lang/String"), 33,
            "repeat", "isBlank", "strip", "stripLeading", "stripTrailing", "lines", "indent", "transform", "formatted",
        ) + listOf(
            api("java/lang/Character", "toString", 33, "(I)Ljava/lang/String;"),
            api("java/lang/CharSequence", "isEmpty", 35),
        )
    }
}

/** Compiled with the same toolchain as the main code: each call here must be flagged. */
@Suppress("unused")
private class NewerApiCanary {
    fun dateOf(now: Instant): LocalDate = LocalDate.ofInstant(now, ZoneOffset.UTC)
    fun timeOf(now: Instant): LocalTime = LocalTime.ofInstant(now, ZoneOffset.UTC)
    fun seconds(d: Duration): Long = d.toSeconds()
    fun minutesPart(d: Duration): Int = d.toMinutesPart()
    fun ratio(a: Duration, b: Duration): Long = a.dividedBy(b)
    fun wholeMinutes(d: Duration): Duration = d.truncatedTo(ChronoUnit.MINUTES)
    fun daysBetween(a: LocalDate, b: LocalDate): List<LocalDate> = a.datesUntil(b).toList()
    fun wrap(x: Long, m: Int): Int = Math.floorMod(x, m)
    fun smallOnes(s: IntStream): IntStream = s.takeWhile { it < 3 }
    fun orFail(o: OptionalInt): Int = o.orElseThrow()
}

/** The Java 8 neighbours of the APIs above: none of these may be flagged. */
@Suppress("unused")
private class AllowedApiCanary {
    fun half(d: Duration): Duration = d.dividedBy(2)
    fun seconds(d: Duration): Long = d.seconds
    fun minutes(d: Duration): Long = d.toMinutes()
    fun wholeMinutes(t: Instant): Instant = t.truncatedTo(ChronoUnit.MINUTES)
    fun dateOf(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()
    fun wrap(x: Long, m: Long): Long = Math.floorMod(x, m)
    fun descending(c: Comparator<Int>): Comparator<Int> = c.reversed()
    fun first(deque: ArrayDeque<Int>): Int = deque.removeFirst()
    fun reversedList(list: List<Int>): List<Int> = list.asReversed()
    fun orFail(o: OptionalInt): Int = o.orElseThrow { IllegalStateException() }
    fun counting(): IntStream = IntStream.iterate(0) { it + 1 }
}
