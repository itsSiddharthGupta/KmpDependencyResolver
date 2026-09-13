package com.kmpdependencyresolver.plugin.ui

data class UserFacingError(
    val code: String,
    val title: String,
    val message: String,
    internal val cause: Throwable? = null,
) {
    companion object {
        fun from(code: String, cause: Throwable? = null): UserFacingError {
            return when (code) {
                "UNSUPPORTED_PROJECT" -> UserFacingError(code, "Project not supported", "Add requires a Kotlin DSL module and gradle/libs.versions.toml.")
                "AMBIGUOUS_SOURCE_SETS" -> UserFacingError(code, "Choose a source set", "The source-set model is ambiguous. Choose one of the listed source sets.")
                "CATALOG_CONFLICT", "ALIAS_COLLISION" -> UserFacingError("CATALOG_CONFLICT", "Catalog conflict", "Choose a different alias or resolve the existing version-catalog entry.")
                "VERSION_CONFLICT" -> UserFacingError(code, "Version conflict", "A different version is already catalogued. Choose that version or update the catalog first.")
                "READ_ONLY" -> UserFacingError(code, "File is read-only", "Make the catalog and build file writable, then preview again.")
                "INVALID_RENDER" -> UserFacingError(code, "Generated syntax is invalid", "No files were changed. Review the project structure and try again.")
                "STALE_STATE" -> UserFacingError(code, "Preview is stale", "The project changed after preview. Review the regenerated confirmation.")
                "PROVIDER_UNAVAILABLE" -> UserFacingError(code, "Provider unavailable", "Search can continue with the remaining providers or cached results.")
                "CACHE_CORRUPT" -> UserFacingError(code, "Search cache is invalid", "The invalid cache entry will be ignored and can be refreshed from the provider.")
                else -> UserFacingError("UNEXPECTED", "Unexpected failure", "The operation was stopped safely. See the IDE log and include the diagnostic code when reporting it.")
            }.copy(cause = cause)
        }
    }
}
