use std::process::Command;

#[test]
fn binary_prints_current_output() {
    let output = Command::new(env!("CARGO_BIN_EXE_termux-rs"))
        .output()
        .expect("run termux-rs binary");

    assert!(output.status.success());
    assert_eq!(b"Hello, world!\n", output.stdout.as_slice());
    assert!(output.stderr.is_empty());
}
