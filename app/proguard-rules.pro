# CampusQuest R8 规则(仅兜底;ML Kit/Room/Compose/OkHttp 均自带 consumer 规则)

# WebView JS 交互方法保留(当前未用 addJavascriptInterface,防未来回归)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp 平台探测的可选依赖告警
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
