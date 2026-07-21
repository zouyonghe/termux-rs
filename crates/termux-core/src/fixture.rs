use crate::emulator::Terminal;
use crate::terminal::{Position, Size};

pub struct Fixture<'a> {
    pub name: &'a str,
    pub size: Size,
    pub input: &'a [u8],
    pub expected_rows: &'a [&'a str],
    pub expected_cursor: Position,
    pub expected_output: &'a [u8],
}

pub fn run_fixture(terminal: &mut Terminal, fixture: Fixture<'_>) -> Result<(), String> {
    if terminal.screen().size() != fixture.size {
        return Err(format!(
            "{}: expected terminal size {:?}, got {:?}",
            fixture.name,
            fixture.size,
            terminal.screen().size()
        ));
    }

    terminal.feed_bytes(fixture.input);
    terminal.finish_input();

    for (row, expected) in fixture.expected_rows.iter().enumerate() {
        let actual = terminal.screen().row_text(row);
        if actual.as_deref() != Some(*expected) {
            return Err(format!(
                "{}: row {row} expected {:?}, got {:?}",
                fixture.name, expected, actual
            ));
        }
    }

    if terminal.screen().cursor() != fixture.expected_cursor {
        return Err(format!(
            "{}: expected cursor {:?}, got {:?}",
            fixture.name,
            fixture.expected_cursor,
            terminal.screen().cursor()
        ));
    }

    if terminal.output() != fixture.expected_output {
        return Err(format!(
            "{}: expected output {:?}, got {:?}",
            fixture.name,
            fixture.expected_output,
            terminal.output()
        ));
    }

    Ok(())
}
