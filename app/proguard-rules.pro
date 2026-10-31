# PdfBox-Android loads fonts/resources by class name and uses reflection
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
# Media3 ships its own consumer rules; keep effect classes it instantiates by name
-dontwarn androidx.media3.**
# Billing ships consumer rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
