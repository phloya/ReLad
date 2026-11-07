package plus.enhance

/**
 * A downloadable enhancement model. Files are fetched from a pinned commit of their official
 * repository and checked against [ModelFile.sha256]; a changed file is never used.
 */
data class EnhanceModel(
    val id: String,
    /** Shown in settings. */
    val name: String,
    val scale: Int,
    /** Border each tile needs so its edges come out right (depends on the network). */
    val prepadding: Int,
    val param: ModelFile,
    val weights: ModelFile,
    val license: ModelLicense,
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

object EnhanceModels {

    /** waifu2x-ncnn-vulkan at the commit whose models were checked (MIT, 2026-04-13). */
    private const val WAIFU2X_COMMIT = "64914665c45893135c9e50c1c296170a121b9f77"
    private const val WAIFU2X_BASE =
        "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/$WAIFU2X_COMMIT/models/models-cunet"
    private const val CUNET_PARAM_SHA = "ccc3b2f1c9fad829ecdda40cac8dc853fcddf14917170efba888b256fbf9074a"

    private val waifu2xLicense = ModelLicense(
        name = "MIT (waifu2x, nagadomi; ncnn conversion, nihui)",
        url = "https://github.com/nihui/waifu2x-ncnn-vulkan/blob/master/LICENSE",
        commercialUse = true,
    )

    private fun cunet(noise: Int, weightsSha: String) = EnhanceModel(
        id = "waifu2x-cunet-2x-noise$noise",
        name = "waifu2x CUnet ×2",
        scale = 2,
        prepadding = 18,
        param = ModelFile("$WAIFU2X_BASE/noise${noise}_scale2.0x_model.param", CUNET_PARAM_SHA, 7_249),
        weights = ModelFile("$WAIFU2X_BASE/noise${noise}_scale2.0x_model.bin", weightsSha, 2_776_400),
        license = waifu2xLicense,
    )

    /** Denoise level 0–3 → model. waifu2x was made for anime and manga art. */
    val cunetByNoise: List<EnhanceModel> = listOf(
        cunet(0, "5329a9375c9cfb64a60a2903fd8ae6e2851d9b069556bf025dbab9d0216fb27c"),
        cunet(1, "862c0e40f3df956ff57bd4d168c85dea5d375b614077fdddd3ac8cfdb9a686da"),
        cunet(2, "5c836e6ae022636cdd5590fbb66dbca2a5fc20bbf8e628884c3612ef72d51858"),
        cunet(3, "f42c18610fecb14a40f75cca9b9276a6889689d5937a4f75f0c2b21bde7e49ed"),
    )

    /** Every model Re:Lad knows. Non-commercial ones would be listed here too, marked in [ModelLicense]. */
    val all: List<EnhanceModel> = cunetByNoise

    /** Models the user may pick: non-commercial ones only after an explicit opt-in. */
    fun available(allowNonCommercial: Boolean): List<EnhanceModel> =
        all.filter { it.license.commercialUse || allowNonCommercial }
}
