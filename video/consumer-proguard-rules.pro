# Kontext Video SDK consumer ProGuard rules, applied by R8 in the consuming app's release build.
#
# None are needed: nothing in the SDK is reached by reflection or by name. The linear ad's Activity is kept
# through the merged manifest, the frame page's messages arrive through androidx.webkit's listener (no
# @JavascriptInterface), and JSON is read and written as JsonElement trees, without generated serializers.
# The app's R8 pass may shrink and obfuscate everything else.
