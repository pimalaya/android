# pimalaya app shrink rules. The JNI-facing keeps live in the :client
# consumer rules (consumer-rules.pro).

# Debug and verbose logs carry OAuth URLs, addresses and raw engine
# replies: release builds strip the calls (and the strings built only for
# them where R8 can tell) rather than ship them to logcat.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
