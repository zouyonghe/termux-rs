fn main() {
    if let Err(error) = termux_core::app::run(std::io::stdout()) {
        eprintln!("termux-rs: {error}");
        std::process::exit(1);
    }
}
