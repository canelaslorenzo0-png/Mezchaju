# Termux terminal emulator/view (native PTY + terminal rendering)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keepclassmembers class com.termux.terminal.JNI { native <methods>; }
