package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class GetApplicationReleaseTest {

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService

    @BeforeEach
    fun beforeEach() {
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService)
    }

    @Test
    fun `When has update but is nightly expect new update`() = runTest {
        val release = Release(
            "r2000",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has update expect new update`() = runTest {
        val release = Release(
            "v2.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        val release = Release(
            "v1.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    // PLUS --> Re:Lad: test versions (0.2.0-beta.1) and versions that differ in more than one number
    @Test
    fun `A release is newer than a test version of it`() = runTest {
        expectUpdate(tag = "relad-v0.2.0", installed = "0.2.0-beta.1", update = true)
        expectUpdate(tag = "relad-v0.2.0", installed = "0.2.0-beta.1-175", update = true)
        expectUpdate(tag = "relad-v0.1.0", installed = "0.2.0-beta.1", update = false)
        expectUpdate(tag = "relad-v0.2.0", installed = "0.2.0", update = false)
    }

    @Test
    fun `An older release is not offered, whatever its later numbers`() = runTest {
        expectUpdate(tag = "relad-v0.1.5", installed = "0.2.0", update = false)
        expectUpdate(tag = "relad-v0.2.1", installed = "0.2.0", update = true)
        expectUpdate(tag = "relad-v0.2", installed = "0.2.0", update = false)
        expectUpdate(tag = "relad-v0.2.0.1", installed = "0.2.0", update = true)
    }

    private suspend fun expectUpdate(tag: String, installed: String, update: Boolean) {
        val release = Release(tag, "info", "http://example.com/release_link", "http://example.com/release_link.apk")
        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = installed,
                repository = "test",
            ),
        )

        (result is GetApplicationRelease.Result.NewUpdate) shouldBe update
    }
    // PLUS <--
}
