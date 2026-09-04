package com.voxit.app.phase2

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxit.app.domain.TranscriptionOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadedAudioPipeline(context: Context, private val modelStore: VoskModelStore = VoskModelStore(context)) {
    private val decoder = AndroidAudioDecoder(context.contentResolver)
    private val speechDetector = EnergySpeechActivityDetector()
    private val featureExtractor = SignalFeatureExtractor()
    private val waveformDownsampler = WaveformDownsampler()
    private val transcriptionEngine = VoskTranscriptionEngine(modelStore)
    private val riskEngine = OfflineConversationRiskEngine()

    suspend fun analyse(file: SelectedAudio, language: String, progress: (PipelineStage) -> Unit): RealAnalysisResult = withContext(Dispatchers.Default) {
        progress(PipelineStage.OPENING); currentCoroutineContext().ensureActive()
        progress(PipelineStage.VALIDATING); currentCoroutineContext().ensureActive()
        progress(PipelineStage.DECODING)
        val decoded = withContext(Dispatchers.IO) { decoder.decode(file) }
        currentCoroutineContext().ensureActive(); progress(PipelineStage.PREPARING)
        val withoutDc = PcmProcessor.removeDcOffset(decoded.monoSamples)
        val resampled = PcmProcessor.resampleLinear(withoutDc, decoded.metadata.sampleRate)
        val prepared = PcmProcessor.normalizeSafely(resampled)
        currentCoroutineContext().ensureActive(); progress(PipelineStage.DETECTING_SPEECH)
        val speech = speechDetector.detect(prepared, AudioLimits.TARGET_SAMPLE_RATE)
        val quality = AudioQualityAnalyzer.analyse(prepared, AudioLimits.TARGET_SAMPLE_RATE, speech)
        currentCoroutineContext().ensureActive(); progress(PipelineStage.EXTRACTING)
        val analysisWindow = prepared.copyOf(minOf(prepared.size, AudioLimits.TARGET_SAMPLE_RATE * 30))
        val features = featureExtractor.extract(analysisWindow, AudioLimits.TARGET_SAMPLE_RATE)
        val waveform = waveformDownsampler.downsample(prepared, AudioLimits.TARGET_SAMPLE_RATE, speech)
        currentCoroutineContext().ensureActive(); progress(PipelineStage.LOADING_MODEL)
        val transcription = if (quality.usableSpeechMs < AudioLimits.MIN_USABLE_SPEECH_MS) TranscriptionOutput.Unavailable("Insufficient speech for transcription.")
        else { progress(PipelineStage.TRANSCRIBING); transcriptionEngine.transcribe(prepared, AudioLimits.TARGET_SAMPLE_RATE, language) }
        currentCoroutineContext().ensureActive(); progress(PipelineStage.ANALYSING_TRANSCRIPT)
        val segments = (transcription as? TranscriptionOutput.Available)?.segments.orEmpty()
        val risk = riskEngine.analyse(segments)
        currentCoroutineContext().ensureActive(); progress(PipelineStage.PREPARING_RESULT)
        RealAnalysisResult(
            metadata = decoded.metadata,
            waveform = waveform,
            speechRegions = speech,
            quality = quality,
            features = features,
            transcript = segments,
            conversationRisk = risk,
            transcriptionModel = (transcription as? TranscriptionOutput.Available)?.modelName,
            transcriptionVersion = (transcription as? TranscriptionOutput.Available)?.modelVersion,
            transcriptionMessage = when (transcription) {
                is TranscriptionOutput.Available -> "Offline transcription completed on this device."
                is TranscriptionOutput.Unavailable -> transcription.reason
                is TranscriptionOutput.Failed -> transcription.reason
            },
        )
    }
}

class Phase2ViewModel(private val applicationContext: Context) : ViewModel() {
    private val pipeline = UploadedAudioPipeline(applicationContext)
    private val modelStore = VoskModelStore(applicationContext)
    private val _uiState = MutableStateFlow<Phase2UiState>(Phase2UiState.Idle)
    val uiState = _uiState.asStateFlow()
    private val initialModelCatalog = modelStore.catalog()
    private val _modelState = MutableStateFlow<ModelImportState>(
        initialModelCatalog.readySelectedModel?.let { ModelImportState.Ready(it) }
            ?: if (initialModelCatalog.selectedModelId == null) ModelImportState.Idle else ModelImportState.Error("Selected model unavailable."),
    )
    val modelState = _modelState.asStateFlow()
    private val _modelCatalog = MutableStateFlow(initialModelCatalog)
    val modelCatalog = _modelCatalog.asStateFlow()
    private var selectedAudio: SelectedAudio? = null
    private var analysisJob: Job? = null
    private var modelJob: Job? = null
    private val sessions = SessionGeneration()
    var preferredLanguage: String = "Auto / Hinglish"

    fun selectAudio(uri: Uri) {
        cancelAnalysis(setCancelled = false)
        try {
            try { applicationContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            selectedAudio = applicationContext.contentResolver.selectedAudio(uri)
            _uiState.value = Phase2UiState.FileSelected(selectedAudio!!)
        } catch (_: SecurityException) {
            selectedAudio = null
            _uiState.value = Phase2UiState.Error("Permission to read this audio file was lost. Select it again.")
        } catch (_: Exception) {
            selectedAudio = null
            _uiState.value = Phase2UiState.Error("The selected audio metadata could not be read. Select another file.")
        }
    }

    fun analyse() {
        val file = selectedAudio ?: return
        if (analysisJob?.isActive == true) return
        val token = sessions.next()
        analysisJob = viewModelScope.launch {
            try {
                val result = pipeline.analyse(file, preferredLanguage) { if (sessions.isCurrent(token)) _uiState.value = Phase2UiState.Working(it) }
                if (sessions.isCurrent(token)) {
                    _uiState.value = Phase2UiState.Working(PipelineStage.COMPLETE)
                    _uiState.value = if (result.transcriptionModel == null) Phase2UiState.ModelRequired(result) else Phase2UiState.Complete(result)
                }
            } catch (_: CancellationException) { if (sessions.isCurrent(token)) _uiState.value = Phase2UiState.Cancelled }
            catch (error: AudioPipelineException) { if (sessions.isCurrent(token)) _uiState.value = Phase2UiState.Error(error.message ?: "Audio processing failed.") }
            catch (error: Exception) { if (sessions.isCurrent(token)) _uiState.value = Phase2UiState.Error("Audio processing failed safely: ${error.message ?: "unknown error"}") }
            finally { analysisJob = null }
        }
    }

    fun cancelAnalysis(setCancelled: Boolean = true) { sessions.invalidate(); analysisJob?.cancel(); analysisJob = null; if (setCancelled) _uiState.value = Phase2UiState.Cancelled }
    fun retry() { analyse() }
    fun reset() { cancelAnalysis(setCancelled = false); selectedAudio = null; _uiState.value = Phase2UiState.Idle }

    fun importModel(uri: Uri, language: String) {
        if (modelJob?.isActive == true) return
        if (liveSessionActive()) {
            _modelState.value = ModelImportState.Error("Stop and restart Live Protection to apply the new transcription model.")
            return
        }
        modelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = modelStore.importZip(uri, language) { _modelState.value = ModelImportState.Importing(it) }
                _modelState.value = ModelImportState.Ready(model)
                _modelCatalog.value = modelStore.catalog()
                clearModelBoundResult()
            } catch (_: CancellationException) { _modelState.value = ModelImportState.Idle }
            catch (error: Exception) { _modelState.value = ModelImportState.Error(error.message ?: "Model import failed.") }
            finally { modelJob = null }
        }
    }

    fun selectModel(modelId: String) {
        if (liveSessionActive()) {
            _modelState.value = ModelImportState.Error("Stop and restart Live Protection to apply the new transcription model.")
            return
        }
        val model = modelStore.selectModel(modelId)
        _modelCatalog.value = modelStore.catalog()
        _modelState.value = model?.let { ModelImportState.Ready(it) } ?: ModelImportState.Error("Selected model unavailable.")
        if (model != null) {
            preferredLanguage = model.language
            clearModelBoundResult()
        }
    }

    fun refreshModels() {
        val catalog = modelStore.catalog()
        val ready = catalog.readySelectedModel
        _modelCatalog.value = catalog
        _modelState.value = when {
            catalog.selectedModelId == null -> ModelImportState.Idle
            ready != null -> ModelImportState.Ready(ready)
            else -> ModelImportState.Error("Selected model unavailable.")
        }
    }

    fun deleteModel() {
        if (liveSessionActive()) {
            _modelState.value = ModelImportState.Error("Stop Live Protection before deleting its transcription model.")
            return
        }
        modelJob?.cancel()
        modelStore.deleteModel()
        refreshModels()
        clearModelBoundResult()
    }
    fun deleteTranscript() {
        val current = when (val state = _uiState.value) { is Phase2UiState.Complete -> state.result; is Phase2UiState.ModelRequired -> state.result; else -> null } ?: return
        val cleared = current.copy(transcript = emptyList(), conversationRisk = ConversationRiskResult(null, emptyList(), "Transcript deleted; conversation-risk output removed."), transcriptSaved = false)
        _uiState.value = if (current.transcriptionModel == null) Phase2UiState.ModelRequired(cleared) else Phase2UiState.Complete(cleared)
    }

    override fun onCleared() { cancelAnalysis(setCancelled = false); modelJob?.cancel(); super.onCleared() }

    private fun liveSessionActive(): Boolean {
        val state = com.voxit.app.live.LiveProtectionStore.state.value
        return state.microphoneActive || state.status in setOf(
            com.voxit.app.live.LiveSessionStatus.PREPARING,
            com.voxit.app.live.LiveSessionStatus.STARTING_SERVICE,
            com.voxit.app.live.LiveSessionStatus.LISTENING,
            com.voxit.app.live.LiveSessionStatus.COLLECTING_SPEECH,
            com.voxit.app.live.LiveSessionStatus.TRANSCRIBING,
            com.voxit.app.live.LiveSessionStatus.PAUSED,
            com.voxit.app.live.LiveSessionStatus.LOW_QUALITY,
            com.voxit.app.live.LiveSessionStatus.AUDIO_BLOCKED,
            com.voxit.app.live.LiveSessionStatus.ALERT,
        )
    }

    private fun clearModelBoundResult() {
        val current = when (val state = _uiState.value) {
            is Phase2UiState.Complete -> state.result
            is Phase2UiState.ModelRequired -> state.result
            else -> null
        } ?: return
        _uiState.value = Phase2UiState.ModelRequired(
            current.copy(
                transcript = emptyList(),
                conversationRisk = ConversationRiskResult(null, emptyList(), "Transcription model changed. Analyse again to create new real transcript evidence."),
                transcriptionModel = null,
                transcriptionVersion = null,
                transcriptionMessage = "Transcription model changed. Previous transcript and transcript-risk state were cleared.",
            ),
        )
    }
}
