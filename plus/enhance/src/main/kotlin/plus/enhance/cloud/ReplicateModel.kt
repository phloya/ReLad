package plus.enhance.cloud

/** An upscaling model on Replicate and how to call it. */
data class ReplicateModel(
    /** Saved in settings. */
    val id: String,
    val name: String,
    /** `owner/name` (official models) or `owner/name:version` (community models, pinned). */
    val model: String,
    /** The input that takes the page image. */
    val imageField: String,
    /** Other inputs, a JSON object. */
    val extraInput: String = "{}",
    val license: String,
    val price: String,
) {
    companion object {
        const val CUSTOM = "custom"
    }
}

/**
 * Presets. Real-ESRGAN is open (BSD-3) and cheap, Topaz is proprietary and the best known photo
 * upscaler. Neither matched the phone's Real-CUGAN on our manga test (see plus/DECISIONS.md):
 * Real-ESRGAN turns screentone into hatching; Topaz was not tested (needs a key).
 */
object ReplicateModels {
    val realEsrgan = ReplicateModel(
        id = "realesrgan",
        name = "Real-ESRGAN x4plus",
        // xinntao/realesrgan, version checked on 2026-11-07 (8.5M runs)
        model = "xinntao/realesrgan:1b976a4d456ed9e4d1a846597b7614e79eadad3032e9124fa63859db0fd59b56",
        imageField = "img",
        extraInput = """{"version":"General - RealESRGANplus","scale":2,"face_enhance":false}""",
        license = "BSD-3-Clause",
        price = "≈ $0.004",
    )

    val topaz = ReplicateModel(
        id = "topaz",
        name = "Topaz Labs Image Upscale",
        model = "topazlabs/image-upscale",
        imageField = "image",
        extraInput = """{"enhance_model":"Standard V2","upscale_factor":"2x"}""",
        license = "Topaz Labs (proprietary)",
        price = "≈ $0.05",
    )

    val all = listOf(realEsrgan, topaz)

    fun byId(id: String): ReplicateModel? = all.firstOrNull { it.id == id }
}
