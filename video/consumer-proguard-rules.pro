# Kontext Video SDK consumer ProGuard rules, applied by R8 in the consuming app's release build.
#
# One rule: show(container) refuses a RecyclerView container (its next layout would crash on the ad's view), found
# by its class name so the SDK needs no RecyclerView dependency; that name must survive the app's R8.
-keepnames class androidx.recyclerview.widget.RecyclerView

# Nothing else is reached by reflection or by name. The linear ad's Activity is kept
# through the merged manifest, the frame page's messages arrive through androidx.webkit's listener (no
# @JavascriptInterface), and JSON is read and written as JsonElement trees, without generated serializers.
# The app's R8 pass may shrink and obfuscate everything else.
