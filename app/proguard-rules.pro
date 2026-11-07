# Keep the Xposed entry class referenced from assets/xposed_init
-keep class com.eta.carrouter.MainHook { *; }
# Keep settings activity and config for external intents / reflection
-keep class com.eta.carrouter.SettingsActivity { *; }
-keep class com.eta.carrouter.Config { *; }
# activity-alias target
-keep class com.eta.carrouter.Launcher { *; }
