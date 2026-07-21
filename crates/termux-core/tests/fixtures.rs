use termux_core::emulator::Terminal;
use termux_core::fixture::{Fixture, run_fixture};
use termux_core::terminal::{Position, Size};

#[test]
fn runs_ported_termux_app_screen_fixture() {
    let fixture = Fixture {
        name: "TerminalTest.testScreen initial write",
        size: Size::new(3, 3),
        input: b"hi",
        expected_rows: &["hi ", "   ", "   "],
        expected_cursor: Position { column: 2, row: 0 },
        expected_output: b"",
    };

    let mut terminal = Terminal::new(fixture.size);
    run_fixture(&mut terminal, fixture).unwrap();
}

#[test]
fn runs_ported_termux_app_cursor_positioning_fixture() {
    let fixture = Fixture {
        name: "TerminalTest.testCursorPositioning",
        size: Size::new(10, 10),
        input: b"\x1b[2;3H\x1b[4;6H\x1b[3;3HA",
        expected_rows: &[
            "          ",
            "          ",
            "  A       ",
            "          ",
            "          ",
            "          ",
            "          ",
            "          ",
            "          ",
            "          ",
        ],
        expected_cursor: Position { column: 3, row: 2 },
        expected_output: b"",
    };

    let mut terminal = Terminal::new(fixture.size);
    run_fixture(&mut terminal, fixture).unwrap();
}

#[test]
fn runs_ported_termux_app_cursor_back_fixture() {
    let fixture = Fixture {
        name: "CursorAndScreenTest.testCursorBack",
        size: Size::new(3, 2),
        input: b"AB\x1b[2DC",
        expected_rows: &["CB ", "   "],
        expected_cursor: Position { column: 1, row: 0 },
        expected_output: b"",
    };

    let mut terminal = Terminal::new(fixture.size);
    run_fixture(&mut terminal, fixture).unwrap();
}
