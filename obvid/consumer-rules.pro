# Native methods are looked up by name from Rust's JNI symbols (`Java_dev_serhiiyaremych_obvid_<Class>_<method>`).
-keepclasseswithmembernames class dev.serhiiyaremych.obvid.ObvidNative {
    native <methods>;
}
