package com.sleepsaver.core.domain.need

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Test

/**
 * core:domain compiles against JDK 17 and is not covered by Android lint, but runs on devices down to
 * minSdk 28. Fails when the debt or need code references a java.time method Android added later
 * (it would throw NoSuchMethodError on those devices). Matches constant-pool strings, so it only knows
 * the unambiguous signatures listed below.
 */
class MinSdkApiTest {

    @Test
    fun `debt and need code only use java time methods available on API 28`() {
        val root = Paths.get(SleepNeedEstimator::class.java.protectionDomain.codeSource.location.toURI())
        val classFiles = listOf("debt", "need").flatMap { classFilesIn(root.resolve("com/sleepsaver/core/domain/$it")) }
        assertThat(classFiles).isNotEmpty()

        for (file in classFiles) {
            val constants = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
            for ((signature, api) in NEWER_THAN_MIN_SDK) {
                assertWithMessage("${root.relativize(file)} uses $signature (API $api)").that(signature in constants).isFalse()
            }
        }
    }

    private fun classFilesIn(dir: Path): List<Path> =
        Files.walk(dir).use { paths -> paths.iterator().asSequence().filter { it.toString().endsWith(".class") }.toList() }

    private companion object {
        val NEWER_THAN_MIN_SDK = mapOf(
            "(Ljava/time/Instant;Ljava/time/ZoneId;)Ljava/time/LocalTime;" to 31, // LocalTime.ofInstant
            "(Ljava/time/Instant;Ljava/time/ZoneId;)Ljava/time/LocalDate;" to 34, // LocalDate.ofInstant
            "datesUntil" to 34,
            "toDaysPart" to 31,
            "toHoursPart" to 31,
            "toMinutesPart" to 31,
            "toSecondsPart" to 31,
            "toMillisPart" to 31,
        )
    }
}
