package plus.enhance

/**
 * A downloadable enhancement model. Files are fetched from a pinned commit of their official
 * repository and checked against [ModelFile.sha256]; a changed file is never used.
 */
data class EnhanceModel(
    val id: String,
    /** Shown in settings. */
    val name: String,
    val strength: Strength,
    val scale: Int,
    /** Border each tile needs so its edges come out right (depends on the network). */
    val prepadding: Int,
    val param: ModelFile,
    val weights: ModelFile,
    val license: ModelLicense,
    val inputBlob: String,
    val outputBlob: String,
    /** Blobs averaged over the whole input (SE blocks): computed once per page and shared by all tiles. */
    val sharedBlobs: List<String> = emptyList(),
    /** Mirror the page at its edges (otherwise repeat the edge pixels). */
    val reflectBorder: Boolean = true,
) {
    val downloadBytes: Long get() = param.size + weights.size
}

data class ModelFile(val url: String, val sha256: String, val size: Long)

data class ModelLicense(
    val name: String,
    val url: String,
    /** `false` for models whose license forbids commercial use: hidden unless the user opts in, never in a paid build. */
    val commercialUse: Boolean,
)

/** How hard the model cleans: stronger removes more noise and JPEG blocks, but also more fine detail. */
enum class Strength {
    /** Keeps the most of the original; for clean scans that are only small. */
    Conservative,
    Light,
    Medium,

    /** For heavily compressed scans: makes the paper white and the lines clean. */
    Strong,
}

object EnhanceModels {

    /** realcugan-ncnn-vulkan at the commit whose models were checked (MIT, nihui; last change to the models 2022-02-15). */
    private const val CUGAN_COMMIT = "395302c5c70f1bff604c974e92e0a87e45c9f9ee"
    private const val CUGAN_BASE =
        "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/$CUGAN_COMMIT/models/models-se"

    /** The same network description for every strength. */
    private const val CUGAN_PARAM_SHA = "4b3b5c5710ad1d00503c8243417aa75f13fe497723e8b4952e8b1abfc08f9b84"

    private val cuganLicense = ModelLicense(
        name = "MIT (Real-CUGAN, bilibili; ncnn conversion, nihui)",
        url = "https://github.com/bilibili/ailab/blob/main/Real-CUGAN/LICENSE",
        commercialUse = true,
    )

    private fun cugan(strength: Strength, file: String, weightsSha: String) = EnhanceModel(
        id = "realcugan-se-$file",
        name = "Real-CUGAN ×2",
        strength = strength,
        scale = 2,
        prepadding = 18,
        param = ModelFile("$CUGAN_BASE/$file.param", CUGAN_PARAM_SHA, 4_761),
        weights = ModelFile("$CUGAN_BASE/$file.bin", weightsSha, 2_573_648),
        license = cuganLicense,
        inputBlob = "in0",
        outputBlob = "out0",
        sharedBlobs = listOf("gap0", "gap1", "gap2", "gap3"),
        reflectBorder = true,
    )

    /**
     * Real-CUGAN: made for anime and manga, trained on real-world damage (blur, noise, JPEG).
     * Chosen over waifu2x CUnet and Real-ESRGAN after a side-by-side test, see plus/DECISIONS.md.
     */
    val cugan: Map<Strength, EnhanceModel> = listOf(
        cugan(
            Strength.Conservative,
            "up2x-conservative",
            "91c72c136e7ff8556323d4449c5ceeff22d9829bf8463a01137cadb8d59b84a0",
        ),
        cugan(Strength.Light, "up2x-denoise1x", "09373c4fddfd2a57fb4af5e516eba1b4c9caa132fd52ffd01bcaefee543bf7f6"),
        cugan(Strength.Medium, "up2x-denoise2x", "47427a52badebfbd7bb6db97196a2844c553f7f2bb93c9527b26347bea0430e7"),
        cugan(Strength.Strong, "up2x-denoise3x", "610ebae63f388fe6f39615cc5f4eaeb038d5aa6ba8b299ba3b989f7f7a1a280a"),
    ).associateBy { it.strength }

    /** Every model Re:Lad knows. Non-commercial ones would be listed here too, marked in [ModelLicense]. */
    val all: List<EnhanceModel> = cugan.values.toList()

    /** Models the user may pick: non-commercial ones only after an explicit opt-in. */
    fun available(allowNonCommercial: Boolean): List<EnhanceModel> =
        all.filter { it.license.commercialUse || allowNonCommercial }
}
