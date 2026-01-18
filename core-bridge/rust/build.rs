use std::io::Result;
use std::path::Path;

fn main() -> Result<()> {
    let proto_path = Path::new("../src/main/proto/temporal/bridge/bridge.proto");
    let out_dir = std::env::var("OUT_DIR").unwrap();
    let generated_file = Path::new(&out_dir).join("temporal.bridge.rs");

    // Re-run if proto files change
    println!("cargo:rerun-if-changed=../src/main/proto");

    // Check if protoc is available
    let protoc_available = std::process::Command::new("protoc")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);

    if protoc_available && proto_path.exists() {
        // Compile protobuf files
        prost_build::compile_protos(
            &["../src/main/proto/temporal/bridge/bridge.proto"],
            &["../src/main/proto"],
        )?;
    } else {
        // Generate empty module file as placeholder
        std::fs::write(&generated_file, "// Protobuf types will be generated here when protoc is available\n")?;
        if !protoc_available {
            println!("cargo:warning=protoc not found, skipping proto generation. Install protobuf to enable.");
        }
    }

    Ok(())
}
