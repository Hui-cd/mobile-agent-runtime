package ai.mobileagent.runtime

data class LaunchableApp(val packageName: String, val label: String)

/** Discover actual launcher apps instead of maintaining per-app name/workflow tables. */
object AppCatalog {
    fun resolve(apps: List<LaunchableApp>, requested: String): String {
        val name = requested.trim()
        require(name.isNotBlank()) { "OPEN_APP_TARGET_MISSING" }
        apps.firstOrNull { it.packageName == name }?.let { return it.packageName }
        val normalized = name.lowercase().removeSuffix(" app").removeSuffix("应用").trim()
        val exact = apps.filter { it.label.lowercase() == normalized }
        val matches = exact.ifEmpty {
            apps.filter { it.label.isNotBlank() && normalized.length >= 2 && it.label.lowercase().contains(normalized) }
        }.distinctBy { it.packageName }
        check(matches.isNotEmpty()) { "APP_NOT_FOUND: use device_observe(include_apps=true) to discover launchable apps" }
        check(matches.size == 1) { "APP_AMBIGUOUS: specify package from ${matches.map { it.packageName }}" }
        return matches.single().packageName
    }
}
