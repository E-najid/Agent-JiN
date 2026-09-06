-keep class com.ngi.agentjin.core.inference.LlamaNative { *; }
-keep class com.ngi.agentjin.core.plugin.PluginFactory
-keep class * implements com.ngi.agentjin.core.plugin.PluginFactory
-keepclassmembers class * implements com.ngi.agentjin.core.plugin.PluginFactory {
    public <init>();
}
-keep class org.bouncycastle.** { *; }
