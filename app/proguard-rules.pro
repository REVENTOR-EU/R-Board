# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# sherpa-onnx JNI: the native lib looks up config/result classes and constructors
# reflectively by name — R8 must not rename or merge them (crash: NoSuchMethodError
# on OfflineRecognizerResult.<init> from Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_getResult)
-keep class com.k2fsa.sherpa.onnx.** { *; }
