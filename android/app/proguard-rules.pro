# The JS bridge is reached by name from JavaScript, so its methods must not be
# renamed or stripped however aggressively the rest of the app is shrunk.
-keepclassmembers class io.github.terpenesalad.jellymusic.WebAppBridge {
    public *;
}
-keepattributes JavascriptInterface
