package top.cxmeow.risingstones.feature.personaldata.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.cxmeow.risingstones.feature.personaldata.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataShareViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun closingCancelsSlowRendererAndLateResultCannotRestorePreview() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        var renderCalls = 0
        val model = PersonalDataShareViewModel()
        model.open(ultimateInput(), resources(), PersonalDataShareRenderer {
            renderCalls++
            withContext(NonCancellable) { gate.await() }
            artifact(1)
        }, hasAccess = { true }, clearExport = { cleanupCalls++ })
        runCurrent()
        assertEquals(PersonalDataShareUiState.Rendering, model.state.value)
        assertEquals(1, renderCalls)

        model.close()
        assertEquals(PersonalDataShareUiState.Closed, model.state.value)
        assertEquals(1, cleanupCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PersonalDataShareUiState.Closed, model.state.value)
        assertEquals(1, cleanupCalls)
    }

    @Test fun openingChangedSourceClearsOldArtifactAndIgnoresUncooperativeOldRenderer() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val oldArtifact = artifact(1)
        val newArtifact = artifact(2)
        var cleanupCalls = 0
        var renderCalls = 0
        val model = PersonalDataShareViewModel()
        val renderer = PersonalDataShareRenderer { document ->
            renderCalls++
            if (document.identity.characterName == "Old") {
                withContext(NonCancellable) { oldGate.await() }
                oldArtifact
            } else newArtifact
        }
        model.open(ultimateInput("Old"), resources(), renderer, { true }) { cleanupCalls++ }
        runCurrent()
        model.open(ultimateInput("New"), resources(), renderer, { true }) { cleanupCalls++ }
        advanceUntilIdle()
        val preview = model.state.value as PersonalDataShareUiState.Preview
        assertSame(newArtifact, preview.artifact)
        assertEquals(2, renderCalls)
        assertEquals(1, cleanupCalls)

        oldGate.complete(Unit)
        advanceUntilIdle()
        assertSame(newArtifact, (model.state.value as PersonalDataShareUiState.Preview).artifact)
        assertEquals(1, cleanupCalls)
        model.close()
        assertEquals(2, cleanupCalls)
    }

    @Test fun accessRevokedDuringRenderingRejectsResultAndClearsExport() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hasAccess = true
        var cleanupCalls = 0
        val model = PersonalDataShareViewModel()
        model.open(ultimateInput(), resources(), PersonalDataShareRenderer {
            gate.await()
            artifact(1)
        }, hasAccess = { hasAccess }, clearExport = { cleanupCalls++ })
        runCurrent()
        hasAccess = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PersonalDataShareUiState.AuthenticationRequired, model.state.value)
        assertEquals(1, cleanupCalls)
    }

    @Test fun requiredSourceFailureStopsBeforeRendererEvenWhenStaleRecordsRemain() = runTest {
        var renderCalls = 0
        val stale = ultimateInput().copy(state = PersonalDataUltimateUiState(
            overviewStatus = PersonalDataUltimateLoadStatus.Failed,
            overviewFailure = PersonalDataUltimateFailure.LoadFailed,
            records = listOf(ultimateRecord()),
            zone = ZoneId.of("UTC"),
        ))
        val model = PersonalDataShareViewModel()
        model.open(stale, resources(), PersonalDataShareRenderer { renderCalls++; artifact(1) }, { true })
        advanceUntilIdle()
        assertEquals(PersonalDataShareUiState.NotReady(PersonalDataShareNotReadyReason.Failed), model.state.value)
        assertEquals(0, renderCalls)
    }

    @Test fun duplicateShareWhileSendingStartsOnlyOneExternalSend() = runTest {
        val model = readyModel()
        val gate = CompletableDeferred<Unit>()
        var sends = 0
        val send: suspend (PersonalDataShareArtifact) -> Unit = {
            sends++
            gate.await()
        }
        model.share({ true }, send)
        runCurrent()
        assertTrue((model.state.value as PersonalDataShareUiState.Preview).isSharing)
        model.share({ true }, send)
        runCurrent()
        assertEquals(1, sends)
        gate.complete(Unit)
        advanceUntilIdle()
        val preview = model.state.value as PersonalDataShareUiState.Preview
        assertFalse(preview.isSharing)
        assertFalse(preview.shareFailed)
        assertEquals(1, sends)
    }

    @Test fun shareFailureRetainsSamePreviewAndAllowsRetry() = runTest {
        val model = readyModel()
        val before = model.state.value as PersonalDataShareUiState.Preview
        var sends = 0
        model.share({ true }) { sends++; error("external share failed") }
        advanceUntilIdle()
        val failed = model.state.value as PersonalDataShareUiState.Preview
        assertSame(before.artifact, failed.artifact)
        assertFalse(failed.isSharing)
        assertTrue(failed.shareFailed)

        model.share({ true }) { sends++ }
        advanceUntilIdle()
        val retried = model.state.value as PersonalDataShareUiState.Preview
        assertSame(before.artifact, retried.artifact)
        assertFalse(retried.isSharing)
        assertFalse(retried.shareFailed)
        assertEquals(2, sends)
    }

    @Test fun sameViewModelStoreAfterRotationKeepsBytesWithoutRenderingAgain() = runTest {
        val owner = ShareOwner()
        var renderCalls = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonalDataShareViewModel() as T
        }
        val first = ViewModelProvider(owner, factory)[PersonalDataShareViewModel::class.java]
        first.open(ultimateInput(), resources(), PersonalDataShareRenderer { renderCalls++; artifact(7) }, { true })
        advanceUntilIdle()
        val before = first.state.value as PersonalDataShareUiState.Preview

        val afterRotation = ViewModelProvider(owner, factory)[PersonalDataShareViewModel::class.java]
        assertSame(first, afterRotation)
        assertSame(before.artifact, (afterRotation.state.value as PersonalDataShareUiState.Preview).artifact)
        assertArrayEquals(byteArrayOf(7), before.artifact.pngBytes())
        assertEquals(1, renderCalls)
        owner.viewModelStore.clear()
    }

    private suspend fun TestScope.readyModel(): PersonalDataShareViewModel {
        val model = PersonalDataShareViewModel()
        model.open(ultimateInput(), resources(), PersonalDataShareRenderer { artifact(1) }, { true })
        advanceUntilIdle()
        assertTrue(model.state.value is PersonalDataShareUiState.Preview)
        return model
    }

    private fun ultimateInput(name: String = "Character") = PersonalDataShareInput.Ultimate(
        PersonalDataIdentity(name, "Area", "World", null),
        PersonalDataUltimateUiState(
            overviewStatus = PersonalDataUltimateLoadStatus.Loaded,
            records = listOf(ultimateRecord()),
            zone = ZoneId.of("UTC"),
        ),
    )

    private fun ultimateRecord() = PersonalDataUltimateRecord(
        733, 1, 1, "Job", UltimateRecordTime.OffsetTime(Instant.EPOCH), 100, 1)

    private fun resources() = object : PersonalDataShareResourceService {
        override suspend fun fetchShareCatalogs() = PersonalDataShareCatalogs(ultimateAchievements = listOf(
            UltimateShareAchievement(733, 1993, 1, "Achievement", "Detail")))
        override fun sharePageUrl(kind: PersonalDataShareKind) = "https://example.invalid/${kind.name}"
        override fun shareImageUrl(image: PersonalDataShareImage): String? = null
    }

    private fun artifact(value: Int) = PersonalDataShareArtifact(byteArrayOf(value.toByte()), 10, 20, "preview")

    private class ShareOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
