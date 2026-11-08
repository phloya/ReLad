package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isNightly,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isNightly: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isNightly) {
            // Nightly builds: based on releases in "mihonapp/mihon-preview" repo
            // tagged as something like "r1234"
            newVersion.toInt() > commitCount
        } else {
            // PLUS --> Re:Lad: numbers are compared in order, and a test version (0.2.0-beta.1) is older
            // than its release (0.2.0). Mihon's loop called 0.1.5 newer than 0.2.0, read 0.2.0-beta.1
            // as 0.2.0.1 and failed when the release had fewer numbers than the installed version.
            plusIsNewer(versionTag, versionName)
            // PLUS <--
        }
    }

    // PLUS -->

    /** The published release [versionTag] ("relad-v0.2.0") is newer than the installed [versionName]. */
    private fun plusIsNewer(versionTag: String, versionName: String): Boolean {
        val new = plusVersion(versionTag) ?: return false
        val old = plusVersion(versionName) ?: return false
        for (i in 0 until maxOf(new.numbers.size, old.numbers.size)) {
            val n = new.numbers.getOrElse(i) { 0 }
            val o = old.numbers.getOrElse(i) { 0 }
            if (n != o) return n > o
        }
        // The same numbers: the release is newer than a test version of it
        return old.test && !new.test
    }

    private class PlusVersion(val numbers: List<Int>, val test: Boolean)

    /** "relad-v0.2.0" → 0.2.0; "0.2.0-beta.1" and its debug build "0.2.0-beta.1-175" → 0.2.0, a test version. */
    private fun plusVersion(version: String): PlusVersion? {
        val start = version.indexOfFirst { it.isDigit() }
        if (start < 0) return null
        val rest = version.substring(start)
        val numbers = rest.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
        return PlusVersion(numbers, '-' in rest)
    }
    // PLUS <--

    data class Arguments(
        val isFoss: Boolean,
        val isNightly: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
