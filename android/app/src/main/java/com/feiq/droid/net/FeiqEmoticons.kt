package com.feiq.droid.net

/** FeiQ built-in emoticon text codes, mapped to bundled emoji assets. */
object FeiqEmoticons {
    /*
     * Captured from FeiQ.exe's built-in emoticon panel tooltips on 2026-06-08.
     * This is the actual desktop panel order: 16 columns x 6 rows, 96 items.
     */
    private val codes = listOf(
        "/:)", "/:~", "/:*", "/:|", "/8-)", "/:<", "/:$", "/:X",
        "/:Z", "/:'(", "/:-|", "/:@", "/:P", "/:D", "/:O", "/<rotate>",
        "/:(", "/:+", "/:lenhan", "/:Q", "/:T", "/;P", "/;-D", "/;d",
        "/;o", "/:g", "/|-)", "/:!", "/:L", "/:>", "/;bin", "/:fw",
        "/;fd", "/:-S", "/;?", "/;x", "/;@", "/:8", "/;!", "/!!!",
        "/:xx", "/:bye", "/:csweat", "/:knose", "/:applause", "/:cdale", "/:huaixiao", "/:shake",
        "/:lhenhen", "/:rhenhen", "/:yawn", "/:snooty", "/:chagrin", "/:kcry", "/:yinxian", "/:qinqin",
        "/:xiaren", "/:kelin", "/:caidao", "/:xig", "/:bj", "/:basketball", "/:pingpong", "/:jump",
        "/:coffee", "/:eat", "/:pig", "/:rose", "/:fade", "/:kiss", "/:heart", "/:break",
        "/:cake", "/:shd", "/:bomb", "/:dao", "/:footb", "/:piaocon", "/:shit", "/:oh",
        "/:moon", "/:sun", "/;gift", "/:hug", "/:strong", "/;weak", "/:share", "/:shl",
        "/:baoquan", "/:cajole", "/:quantou", "/:chajin", "/:aini", "/:sayno", "/:sayok", "/:love",
    )

    val codeToAsset: Map<String, String> = codes.mapIndexed { i, code -> code to "${i + 1}.gif" }.toMap()
    val assetToCode: Map<String, String> = codeToAsset.entries.associate { it.value to it.key }
    private val orderedCodes = codeToAsset.keys.sortedByDescending { it.length }

    fun assetForCode(code: String): String? = codeToAsset[code]
    fun codeForAsset(asset: String): String? = assetToCode[asset]

    fun replaceCodesWithTokens(text: String): String {
        var out = text
        orderedCodes.forEach { code ->
            val asset = codeToAsset[code] ?: return@forEach
            out = out.replace(code, FeiqRichText.emojiToken(asset))
        }
        return out
    }
}
