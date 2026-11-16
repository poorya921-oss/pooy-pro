# gomobile (Xray-core AAR): classes are looked up by name from Go via JNI
-keep class libv2ray.** { *; }
-keep class go.** { *; }
# Our implementation of the core's callback interface is invoked from Go
-keep class * implements libv2ray.CoreCallbackHandler { *; }

# hev-socks5-tunnel JNI registers these natives by class/method name
-keep class com.pooy.vpn.service.TProxyService { *; }
-keepclasseswithmembernames class * { native <methods>; }

# XrayCore calls CoreController.startLoop/queryStats reflectively (covered by libv2ray.** above)
-dontwarn go.**
