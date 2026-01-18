use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Arc;
use tokio::runtime::Runtime;

#[allow(dead_code)]
mod bridge_proto {
    include!(concat!(env!("OUT_DIR"), "/temporal.bridge.rs"));
}

/// Global Tokio runtime for async operations
static RUNTIME: Lazy<Arc<Runtime>> = Lazy::new(|| {
    Arc::new(
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("Failed to create Tokio runtime"),
    )
});

/// Called when the native library is loaded.
/// Initializes the Tokio runtime.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: jni::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    // Force initialization of the runtime
    let _ = &*RUNTIME;
    jni::sys::JNI_VERSION_1_8
}

/// Returns the version of the native library.
/// JNI signature: ()Ljava/lang/String;
#[no_mangle]
pub extern "system" fn Java_com_surrealdev_temporal_core_internal_TemporalCoreBridge_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = env!("CARGO_PKG_VERSION");
    env.new_string(version)
        .expect("Failed to create Java string")
        .into_raw()
}

/// Simple echo function to verify JNI communication works.
/// JNI signature: (Ljava/lang/String;)Ljava/lang/String;
#[no_mangle]
pub extern "system" fn Java_com_surrealdev_temporal_core_internal_TemporalCoreBridge_nativeEcho(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input_str: String = env
        .get_string(&input)
        .expect("Failed to get string from JNI")
        .into();

    let output_str = format!("Echo from Rust: {}", input_str);
    env.new_string(output_str)
        .expect("Failed to create Java string")
        .into_raw()
}

#[cfg(test)]
mod tests {
    #[test]
    fn test_version() {
        assert_eq!(env!("CARGO_PKG_VERSION"), "0.1.0");
    }
}
