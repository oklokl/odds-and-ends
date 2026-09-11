# Add project specific ProGuard rules here.
# PDFBox rules
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.pdfbox.**
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
