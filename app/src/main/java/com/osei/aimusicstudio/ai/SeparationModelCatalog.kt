package com.osei.aimusicstudio.ai

enum class StemMode(val stems: List<String>) {
    TWO_STEM_VOCAL_INST(listOf("vocals", "instrumental")),
    FOUR_STEM(listOf("vocals", "drums", "bass", "other")),
    SIX_STEM_HTDEMUCS(listOf("drums", "bass", "other", "vocals", "guitar", "piano")),
    SIX_STEM_INSTRUMENTS(listOf("drums", "bass", "guitar", "strings", "keys", "other"))
}

enum class HardwareCompatibility(val badge: String) {
    RECOMMENDED_4GB("4GB RAM Safe"),
    CAUTION_4GB("4GB RAM Tight (2s Chunks)"),
    UNSUPPORTED_4GB("Requires 6-8GB+ RAM")
}

enum class ModelAvailability(val label: String) {
    AVAILABLE("Available"),
    REQUIRES_HIGH_RAM("Blocked on 4GB RAM"),
    UNAVAILABLE("Unavailable")
}

data class SeparationModelSpec(
    val id: String,
    val displayName: String,
    val architecture: String,
    val stemMode: StemMode,
    val outputStemOrder: List<String> = stemMode.stems,
    val fileName: String,
    val downloadUrl: String?,
    val approximateDiskMb: Int,
    val estimatedPeakRamMb: Int,
    val quantization: String,
    val sourceRepository: String,
    val codeLicense: String,
    val weightLicense: String,
    val compatibility: HardwareCompatibility,
    val notes: String,
    val availability: ModelAvailability = ModelAvailability.AVAILABLE,
    val inputSampleRateHz: Int = 44100,
    val inputChannels: Int = 2,
    val tensorSpecSummary: String = "44.1 kHz Stereo",
    val unavailableReason: String? = null
)

object SeparationModelCatalog {
    val models: List<SeparationModelSpec> = listOf(
        SeparationModelSpec(
            id = "mdx_kim_vocal_2_onnx",
            displayName = "UVR MDX-Net Kara 2 (2-Stem)",
            architecture = "MDX-Net STFT TDF-Net (ONNX)",
            stemMode = StemMode.TWO_STEM_VOCAL_INST,
            outputStemOrder = listOf("vocals", "instrumental"),
            fileName = "UVR_MDXNET_KARA_2.onnx",
            downloadUrl = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_KARA_2.onnx",
            approximateDiskMb = 53,
            estimatedPeakRamMb = 230,
            quantization = "FP32/FP16 ONNX opset 14",
            sourceRepository = "https://github.com/Anjok07/ultimatevocalremovergui",
            codeLicense = "MIT License",
            weightLicense = "MIT License (Open commercial redistribution permitted)",
            compatibility = HardwareCompatibility.RECOMMENDED_4GB,
            notes = "Community UVR ONNX release. Extracts Vocals & Instrumental via chunked 44.1 kHz stereo STFT; recommended for 4 GB RAM devices.",
            availability = ModelAvailability.AVAILABLE,
            tensorSpecSummary = "Input [1, 4, F, T] STFT -> Output [1, 4, F, T] @ 44.1 kHz"
        ),
        SeparationModelSpec(
            id = "spleeter_4stems_quant",
            displayName = "Spleeter 4-Stem Quantized (4-Stem)",
            architecture = "U-Net Spectrogram Mask (ONNX INT8)",
            stemMode = StemMode.FOUR_STEM,
            outputStemOrder = listOf("vocals", "drums", "bass", "other"),
            fileName = "spleeter_4stems_int8.onnx",
            downloadUrl = null,
            approximateDiskMb = 39,
            estimatedPeakRamMb = 340,
            quantization = "INT8 Dynamic Quantization",
            sourceRepository = "https://github.com/deezer/spleeter",
            codeLicense = "MIT License",
            weightLicense = "MIT License (Open commercial redistribution permitted)",
            compatibility = HardwareCompatibility.CAUTION_4GB,
            notes = "Lightweight 4-stem U-Net (Vocals, Drums, Bass, Other). Import local quantized .onnx via SAF. Runs on 4 GB phones using 2-second streaming chunks.",
            availability = ModelAvailability.AVAILABLE,
            tensorSpecSummary = "4-tensor spectrogram masks [1, 2, F, T] @ 44.1 kHz"
        ),
        SeparationModelSpec(
            id = "htdemucs_v4_onnx",
            displayName = "HTDemucs v4 Community ONNX (4-Stem)",
            architecture = "Hybrid Transformer Demucs (ONNX opset 17)",
            stemMode = StemMode.FOUR_STEM,
            outputStemOrder = listOf("drums", "bass", "other", "vocals"),
            fileName = "htdemucs_fp16weights.onnx",
            downloadUrl = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
            approximateDiskMb = 166,
            estimatedPeakRamMb = 1450,
            quantization = "FP16-stored weights (opset 17; FP32 runtime activations ~1.2-2.4 GB)",
            sourceRepository = "https://huggingface.co/StemSplitio/htdemucs-onnx (community export via github.com/StemSplit/demucs-onnx; upstream: github.com/facebookresearch/demucs)",
            codeLicense = "MIT License (facebookresearch/demucs & StemSplit/demucs-onnx)",
            weightLicense = "Research/non-commercial caveat: verify upstream Meta Demucs #327 & MUSDB18 terms before commercial redistribution.",
            compatibility = HardwareCompatibility.UNSUPPORTED_4GB,
            notes = "Community ONNX export (NOT an official Meta release). Outputs 4 stems only (drums, bass, other, vocals); runtime memory exceeds 4 GB Android RAM limits.",
            availability = ModelAvailability.REQUIRES_HIGH_RAM,
            tensorSpecSummary = "Input [1, 2, 343980] -> Output [1, 4, 2, 343980] @ 44.1 kHz",
            unavailableReason = "HTDemucs v4 ONNX (~1.45 GB peak RAM) is blocked on <= 4 GB RAM devices unless explicitly overridden on high-RAM hardware."
        ),
        SeparationModelSpec(
            id = "htdemucs_6s_onnx",
            displayName = "HTDemucs 6-Stem Community ONNX (Drums/Bass/Other/Vocals/Guitar/Piano)",
            architecture = "Hybrid Transformer Demucs 6-Stem (ONNX opset 17)",
            stemMode = StemMode.SIX_STEM_HTDEMUCS,
            outputStemOrder = listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            fileName = "htdemucs_6s_fp16weights.onnx",
            downloadUrl = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx",
            approximateDiskMb = 136,
            estimatedPeakRamMb = 1500,
            quantization = "FP16-stored weights (136 MB; FP32 version 258 MB; ~1.2-2.4 GB runtime activations)",
            sourceRepository = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx (community export; upstream: github.com/facebookresearch/demucs)",
            codeLicense = "MIT License (StemSplitio/htdemucs-6s-onnx & facebookresearch/demucs)",
            weightLicense = "Repo declares MIT; upstream Meta htdemucs_6s weights carry MUSDB18/Meta research licensing caveats — verify before commercial redistribution.",
            compatibility = HardwareCompatibility.UNSUPPORTED_4GB,
            notes = "Does NOT separate Strings or Keys: exact tensor outputs are [drums, bass, other, vocals, guitar, piano]. Never renamed to Strings/Keys. Fixed 7.8s chunks exceed 4 GB Android RAM.",
            availability = ModelAvailability.REQUIRES_HIGH_RAM,
            tensorSpecSummary = "Input [1, 2, 343980] -> Output [1, 6, 2, 343980] @ 44.1 kHz",
            unavailableReason = "HTDemucs 6-Stem outputs [drums, bass, other, vocals, guitar, piano] (no Strings or Keys stem) and requires ~1.5+ GB runtime RAM, exceeding 4 GB Android hardware limits."
        ),
        SeparationModelSpec(
            id = "bs_roformer_sw_6s",
            displayName = "BS-RoFormer-SW 6-Stem (Bass/Drums/Other/Vocals/Guitar/Piano)",
            architecture = "Band-Split RoPE Transformer (BS-RoFormer 6-Stem)",
            stemMode = StemMode.SIX_STEM_HTDEMUCS,
            outputStemOrder = listOf("bass", "drums", "other", "vocals", "guitar", "piano"),
            fileName = "bs_roformer_sw_6stem_fp16.onnx",
            downloadUrl = null,
            approximateDiskMb = 336,
            estimatedPeakRamMb = 2600,
            quantization = "FP16 ONNX (336 MB; FP32 669 MB; PyTorch .ckpt 699 MB)",
            sourceRepository = "https://huggingface.co/elicwhite/bs-roformer-web (checkpoint: BS-Rofo-SW-Fixed.ckpt)",
            codeLicense = "MIT License (elicwhite/bs-roformer-web ONNX wrapper)",
            weightLicense = "Upstream checkpoint license is Unknown (not cleared for unconditional commercial redistribution).",
            compatibility = HardwareCompatibility.UNSUPPORTED_4GB,
            notes = "Outputs Vocals and Piano instead of Strings and Keys; multi-GB RAM and heavy CPU load make it unusable on a 4 GB Android phone.",
            availability = ModelAvailability.UNAVAILABLE,
            tensorSpecSummary = "44.1 kHz stereo -> [bass, drums, other, vocals, guitar, piano]",
            unavailableReason = "BS-RoFormer-SW outputs Vocals/Piano (no Strings/Keys), carries an unknown upstream weight license, and exceeds 4 GB Android phone RAM."
        ),
        SeparationModelSpec(
            id = "msst_bs_roformer_53s",
            displayName = "ZFTurbo / MVSep Mega 53-Stem BS-RoFormer (Includes Strings & Keys)",
            architecture = "BS-RoFormer 53-Stem Multi-Instrument (PyTorch)",
            stemMode = StemMode.SIX_STEM_INSTRUMENTS,
            outputStemOrder = listOf("drums", "bass", "guitar", "strings", "keys", "other"),
            fileName = "mvsep_mega_model_bs_roformer_53_stems_v1.ckpt",
            downloadUrl = null,
            approximateDiskMb = 1370,
            estimatedPeakRamMb = 12288,
            quantization = "PyTorch FP32 checkpoint (~1.37 GB; requires 12-16+ GB RAM/VRAM)",
            sourceRepository = "https://github.com/ZFTurbo/Music-Source-Separation-Training",
            codeLicense = "MIT License",
            weightLicense = "MIT License (ZFTurbo MSST official releases)",
            compatibility = HardwareCompatibility.UNSUPPORTED_4GB,
            notes = "Only researched open-source model family containing explicit strings and keys classes, but it is a 1.37 GB 53-stem PyTorch checkpoint requiring 12-16+ GB memory with no verified 4 GB Android ONNX build.",
            availability = ModelAvailability.UNAVAILABLE,
            tensorSpecSummary = "53-stem PyTorch spectrogram model (~1.37 GB) -> requires server/desktop GPU (12-16+ GB)",
            unavailableReason = "ZFTurbo 53-Stem BS-RoFormer (~1.37 GB checkpoint, 12-16+ GB RAM) cannot run on a 4 GB Android phone and has no verified mobile ONNX export."
        ),
        SeparationModelSpec(
            id = "osei_6stem_instruments_target",
            displayName = "6-Instrument AI Model (Drums, Bass, Guitar, Strings, Keys, Other)",
            architecture = "No Verified 4 GB Android ONNX Model Available",
            stemMode = StemMode.SIX_STEM_INSTRUMENTS,
            outputStemOrder = listOf("drums", "bass", "guitar", "strings", "keys", "other"),
            fileName = "unavailable_6stem_instruments.onnx",
            downloadUrl = null,
            approximateDiskMb = 0,
            estimatedPeakRamMb = 0,
            quantization = "N/A — Unavailable",
            sourceRepository = "Researched: StemSplitio/htdemucs-6s-onnx, BS-RoFormer-SW, ZFTurbo MSST",
            codeLicense = "N/A",
            weightLicense = "N/A",
            compatibility = HardwareCompatibility.UNSUPPORTED_4GB,
            notes = "Six-instrument AI separation (Drums, Bass, Guitar, Strings, Keys, Other) is marked UNAVAILABLE: no verified open-source ONNX model outputs those exact 6 stems within a 4 GB Android RAM budget. Use the 6-Track Mixer to load real audio files per instrument.",
            availability = ModelAvailability.UNAVAILABLE,
            tensorSpecSummary = "Target: [drums, bass, guitar, strings, keys, other] @ 44.1 kHz (Unavailable)",
            unavailableReason = "Six-instrument AI separation (Drums, Bass, Guitar, Strings, Keys, Other) is unavailable: no verified open-source 4 GB-compatible Android ONNX model outputs these exact six stems."
        ),
        SeparationModelSpec(
            id = "local_custom_onnx",
            displayName = "User-Imported Local ONNX Model",
            architecture = "Custom ONNX (STFT [1,4,F,T] or Waveform [1,2,N])",
            stemMode = StemMode.TWO_STEM_VOCAL_INST,
            outputStemOrder = listOf("vocals", "instrumental"),
            fileName = "custom_local_model.onnx",
            downloadUrl = null,
            approximateDiskMb = 0,
            estimatedPeakRamMb = 250,
            quantization = "User-supplied (.onnx)",
            sourceRepository = "Local Device Storage (SAF)",
            codeLicense = "User-verified",
            weightLicense = "User-verified",
            compatibility = HardwareCompatibility.RECOMMENDED_4GB,
            notes = "Load a compatible quantized 2-stem ONNX model directly from device storage without internet.",
            availability = ModelAvailability.AVAILABLE
        )
    )

    fun findById(id: String): SeparationModelSpec {
        return models.firstOrNull { it.id == id } ?: models.first()
    }
}
