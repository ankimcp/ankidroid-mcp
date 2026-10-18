package ai.ankimcp.ankidroid.service

import android.os.Build

/**
 * Android skins whose background freezers stop apps even while they run a foreground service
 * (stock Android does not), with the names of the settings that exempt an app there. Both this
 * app and AnkiDroid need the setting: a frozen AnkiDroid cannot answer the provider (D44).
 */
enum class FreezingOem(
    private val brands: Set<String>,
    /** The brands as users know them, for messages ("Xiaomi, Redmi and Poco phones ..."). */
    val displayName: String,
    /** Where to find the maker's own switches in one app's App info page, in the maker's words. */
    private val path: String,
) {
    ColorOs(
        setOf("oppo", "realme", "oneplus"),
        "Realme, Oppo and OnePlus",
        "App info > Battery usage: turn on \"Allow background activity\" and set battery " +
            "optimisation to \"Don't optimise\".",
    ),
    Xiaomi(
        setOf("xiaomi", "redmi", "poco"),
        "Xiaomi, Redmi and Poco",
        "App info: turn on \"Autostart\", and under Battery saver choose \"No restrictions\".",
    ),
    Vivo(
        setOf("vivo", "iqoo"),
        "Vivo and iQOO",
        "App info > Battery: turn on \"Allow high background power consumption\" (called " +
            "\"Allow background activity\" on some versions).",
    ),
    Huawei(
        setOf("huawei", "honor"),
        "Huawei and Honor",
        "App info > Battery > App launch: turn off \"Manage automatically\" and leave \"Run in " +
            "background\" on.",
    ),
    ;

    /** The same steps for both apps, which each have their own App info page. */
    val instructions: String
        get() = "Do this for both AnkiMCP and AnkiDroid. $path"

    companion object {
        /** Matches `Build.MANUFACTURER` and `Build.BRAND`, ignoring case. */
        fun detect(manufacturer: String?, brand: String?): FreezingOem? {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            return entries.firstOrNull { oem -> names.any { it in oem.brands } }
        }

        fun current(): FreezingOem? = detect(Build.MANUFACTURER, Build.BRAND)
    }
}
