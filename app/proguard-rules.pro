# Methods called from content.js through addJavascriptInterface must keep their names.
-keepclassmembers class com.goomero.cleartv.Blocker$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Smaller dex: move all classes into one package with short names, allow widening access for inlining/merging.
-repackageclasses ''
-allowaccessmodification
