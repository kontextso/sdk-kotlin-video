# The app's own rules, as a publisher's app has them. The Tencent Cloud Player SDK asks apps to keep its classes:
# its native code finds them by name, and without this rule it aborts when the first TXVodPlayer is created. The
# SDK and its adapters need nothing here: they ship their own consumer rules.
-keep class com.tencent.** { *; }
