use std::io::{self, Write};

const GREETING: &str = "Hello, world!";

pub fn run(mut output: impl Write) -> io::Result<()> {
    writeln!(output, "{GREETING}")
}

#[cfg(test)]
mod tests {
    use super::run;

    #[test]
    fn run_writes_current_cli_output() {
        let mut output = Vec::new();

        run(&mut output).unwrap();

        assert_eq!(b"Hello, world!\n", output.as_slice());
    }
}
