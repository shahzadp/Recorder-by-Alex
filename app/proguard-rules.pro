# R8 rules for the release build.
#
# The libraries this app uses (CameraX, Material, AndroidX) ship their own
# consumer rules, so only the app specific parts need to be listed here.

# The application class is referenced from the manifest as well.
-keep class com.alex.cam.RecorderApp { *; }

# Keep the line numbers for readable crash reports, but hide the original
# source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
