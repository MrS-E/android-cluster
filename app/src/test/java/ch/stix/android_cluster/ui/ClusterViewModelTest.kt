package ch.stix.android_cluster.ui

import android.net.Uri
import ch.stix.android_cluster.data.AppSettings
import ch.stix.android_cluster.data.BatteryOptimizationManager
import ch.stix.android_cluster.data.ImageRepository
import ch.stix.android_cluster.data.SettingsRepository
import ch.stix.android_cluster.data.StorageRepository
import ch.stix.android_cluster.data.VmImage
import ch.stix.android_cluster.data.VmRepository
import ch.stix.android_cluster.data.VmStorageInfo
import ch.stix.android_cluster.engine.VmArch
import ch.stix.android_cluster.engine.VmState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClusterViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher

    @BeforeEach
    fun setUp() {
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given image selection changes while picker is open when folder result returns then ignores result`() =
        runTest {
            val harness = createHarness(AppSettings(selectedImage = FIRST_IMAGE), isSyncing = false)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.viewModel.uiState.collect()
            }
            runCurrent()

            harness.settings.value = AppSettings(selectedImage = SECOND_IMAGE)
            runCurrent()
            assertEquals(SECOND_IMAGE, harness.viewModel.uiState.value.selectedImage)

            harness.viewModel.setStorageFolder(FIRST_IMAGE, mockk<Uri>())
            runCurrent()

            coVerify(exactly = 0) {
                harness.storageRepository.setCustomStorageFolder(FIRST_IMAGE, any<Uri>())
            }
        }

    @Test
    fun `given storage sync is active when changing shared folder then ignores request`() = runTest {
        val harness = createHarness(AppSettings(selectedImage = FIRST_IMAGE), isSyncing = true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect()
        }
        runCurrent()

        assertTrue(harness.viewModel.uiState.value.storageInfo?.isSyncing == true)
        harness.viewModel.setStorageFolder(FIRST_IMAGE, mockk<Uri>())
        runCurrent()

        coVerify(exactly = 0) {
            harness.storageRepository.setCustomStorageFolder(FIRST_IMAGE, any<Uri>())
        }
    }

    private fun createHarness(settings: AppSettings, isSyncing: Boolean): Harness {
        val vmRepository = mockk<VmRepository>(relaxed = true)
        every { vmRepository.state } returns MutableStateFlow(VmState.Idle)
        every { vmRepository.consoleLines } returns MutableStateFlow(emptyList())
        every { vmRepository.isEngineInstalled(VmArch.ARM) } returns true

        val imageRepository = mockk<ImageRepository>(relaxed = true)
        every { imageRepository.images } returns MutableStateFlow(
            listOf(
                VmImage(File(FIRST_IMAGE), FIRST_IMAGE, 1L),
                VmImage(File(SECOND_IMAGE), SECOND_IMAGE, 1L),
            ),
        )

        val settingsFlow = MutableStateFlow(settings)
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns settingsFlow
        every { settingsRepository.mappingError } returns MutableStateFlow(null)

        val storageRepository = mockk<StorageRepository>(relaxed = true)
        every { storageRepository.storageInfo } returns MutableStateFlow(
            VmStorageInfo(
                imageName = FIRST_IMAGE,
                localDirectory = File("/tmp/$FIRST_IMAGE"),
                isSyncing = isSyncing,
            ),
        )

        val batteryManager = mockk<BatteryOptimizationManager>(relaxed = true)
        val viewModel = ClusterViewModel(
            vmRepository = vmRepository,
            imageRepository = imageRepository,
            settingsRepository = settingsRepository,
            batteryManager = batteryManager,
            storageRepository = storageRepository,
        )
        return Harness(viewModel, settingsFlow, storageRepository)
    }

    private data class Harness(
        val viewModel: ClusterViewModel,
        val settings: MutableStateFlow<AppSettings>,
        val storageRepository: StorageRepository,
    )

    private companion object {
        const val FIRST_IMAGE = "first.qcow2"
        const val SECOND_IMAGE = "second.qcow2"
    }
}