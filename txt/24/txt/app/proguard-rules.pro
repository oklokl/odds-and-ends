# Add project specific ProGuard rules here.
# PDFBox rules
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.pdfbox.**
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }

# JPEG2000 (JP2ForAndroid) rules - keep JNI native methods and reflection target for PDFBox JPXFilter
-dontwarn com.gemalto.jp2.**
-keep class com.gemalto.jp2.** { *; }
-keepclasseswithmembernames class com.gemalto.jp2.** {
    native <methods>;
}

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
