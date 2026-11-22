# JNI: native methods and the classes that declare them are looked up by name.
-keepclasseswithmembernames class com.github.borz7zy.rustcore.** {
    native <methods>;
}

# Rust's SQLite adapter calls the framework bindings by JNI name.
-keep class com.github.borz7zy.rustcore.HapticsSqlite { *; }
