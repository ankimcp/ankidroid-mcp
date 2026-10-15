package ai.ankimcp.ankidroid.service

import android.os.Build

/**
 * Android skins whose background freezers stop apps even while they run a foreground service
 * (stock Android does not), with the names of the settings that exempt an app there.
 */
enum class FreezingOem(
    private val brands: Set<String>,
    /** Where to find the maker's own switches, in the maker's words. */
    val instructions: String,
) {
    ColorOs(
        setOf("oppo", "realme", "oneplus"),
        "In App info > Battery usage, turn on \"Allow background activity\", and set battery " +
            "optimisation to \"Don't optimise\".",
    ),
    Xiaomi(
        setOf("xiaomi", "redmi", "poco"),
        "In App info, turn on \"Autostart\", and under Battery saver choose \"No restrictions\".",
    ),
    Vivo(
        setOf("vivo", "iqoo"),
        "In App info > Battery, turn on \"Allow high background power consumption\" (called " +
            "\"Allow background activity\" on some versions).",
    ),
    Huawei(
        setOf("huawei", "honor"),
        "In App info > Battery > App launch, turn off \"Manage automatically\" and leave \"Run in " +
            "background\" on.",
    ),
    ;

    companion object {
        /** Matches `Build.MANUFACTURER` and `Build.BRAND`, ignoring case. */
        fun detect(manufacturer: String?, brand: String?): FreezingOem? {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            return entries.firstOrNull { oem -> names.any { it in oem.brands } }
        }

        fun current(): FreezingOem? = detect(Build.MANUFACTURER, Build.BRAND)
    }
}
