// Top-level build file. Plugins are declared here without applying them so that the
// version catalog stays the single source of truth for toolchain versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
