extension {
    name = "extensions/tidal.mpe"
}

android {
    namespace = "dev.chuk.extension.tidal"
}

dependencies {
    // Compose and RecyclerView APIs the swipe gesture implements. These are never bundled into
    // the extension - the patched app provides them at runtime.
    compileOnly("androidx.compose.ui:ui:1.9.0")
    compileOnly("androidx.compose.ui:ui-unit:1.9.0")
    compileOnly("androidx.compose.ui:ui-graphics:1.9.0")
    compileOnly("androidx.compose.runtime:runtime:1.9.0")
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")
}
