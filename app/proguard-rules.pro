# ML Kit finds its component registrars by name from the manifest and creates them with reflection.
# R8 removes their no-arg constructors, the registrar then cannot be created, and translation fails at
# runtime with a missing translator factory (a null pointer inside ML Kit). Debug builds are not minified,
# so this shows up only in a release build.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
