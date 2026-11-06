package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withContext(Dispatchers.IO) {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isNightlyBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            result
        }
    }
}

val GITHUB_REPO: String by lazy {
    // PLUS --> Re:Lad updates come from its own GitHub releases (no separate preview repo yet)
    "phloya/ReLad"
    // PLUS <--
}

val RELEASE_TAG: String by lazy {
    if (isNightlyBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        // PLUS --> Re:Lad tags are relad-vX.Y.Z: plain vX.Y.Z tags are taken by Tachiyomi/Mihon history
        "relad-v${BuildConfig.VERSION_NAME}"
        // PLUS <--
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
