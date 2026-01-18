use once_cell::sync::Lazy;
use std::ffi::{c_char, c_long, CStr, CString};
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

/// Initialize the native library.
/// Should be called once before using other functions.
/// Returns 0 on success, non-zero on failure.
#[no_mangle]
pub extern "C" fn temporal_init() -> c_long {
    // Force initialization of the runtime
    let _ = &*RUNTIME;
    0
}

/// Returns the version of the native library.
/// The returned string is statically allocated and should not be freed.
#[no_mangle]
pub extern "C" fn temporal_version() -> *const c_char {
    // Use a static CString to avoid allocation/deallocation issues
    static VERSION: Lazy<CString> =
        Lazy::new(|| CString::new(env!("CARGO_PKG_VERSION")).unwrap());
    VERSION.as_ptr()
}

/// Echo function to verify FFM communication works.
///
/// # Safety
/// - `input` must be a valid null-terminated C string
/// - The returned string must be freed by calling `temporal_free_string`
#[no_mangle]
pub unsafe extern "C" fn temporal_echo(input: *const c_char) -> *mut c_char {
    if input.is_null() {
        return std::ptr::null_mut();
    }

    let input_str = match CStr::from_ptr(input).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let output = format!("Echo from Rust: {}", input_str);
    match CString::new(output) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Free a string that was allocated by Rust.
///
/// # Safety
/// - `ptr` must have been returned by a Rust function that allocates strings
/// - `ptr` must not be used after this call
#[no_mangle]
pub unsafe extern "C" fn temporal_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version() {
        let version = temporal_version();
        assert!(!version.is_null());
        let version_str = unsafe { CStr::from_ptr(version).to_str().unwrap() };
        assert_eq!(version_str, "0.1.0");
    }

    #[test]
    fn test_echo() {
        let input = CString::new("Hello FFM!").unwrap();
        let output = unsafe { temporal_echo(input.as_ptr()) };
        assert!(!output.is_null());
        let output_str = unsafe { CStr::from_ptr(output).to_str().unwrap() };
        assert_eq!(output_str, "Echo from Rust: Hello FFM!");
        unsafe { temporal_free_string(output) };
    }
}
