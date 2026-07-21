use crate::session::{TerminalOutput, TerminalSessionClient};
use crate::terminal::{Position, Screen, Size, Style};
use crate::utf8::Utf8Decoder;

const MAX_CONTROL_STRING_LENGTH: usize = 8192;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Terminal {
    screen: Screen,
    saved_primary_screen: Option<Screen>,
    style: Style,
    state: ParserState,
    utf8: Utf8Decoder,
    output: Vec<u8>,
}

impl Terminal {
    pub fn new(size: Size) -> Self {
        Self {
            screen: Screen::new(size),
            saved_primary_screen: None,
            style: Style::default(),
            state: ParserState::Ground,
            utf8: Utf8Decoder::new(),
            output: Vec::new(),
        }
    }

    pub fn screen(&self) -> &Screen {
        &self.screen
    }

    pub fn style(&self) -> Style {
        self.style
    }

    pub fn output(&self) -> &[u8] {
        &self.output
    }

    pub fn feed_bytes(&mut self, bytes: &[u8]) {
        for byte in bytes {
            for value in self.utf8.accept(*byte) {
                self.feed_char(value);
            }
        }
    }

    pub fn feed_bytes_with_output(&mut self, bytes: &[u8], output: &mut impl TerminalOutput) {
        for byte in bytes {
            for value in self.utf8.accept(*byte) {
                if matches!(self.state, ParserState::Ground) && value == '\u{7}' {
                    output.bell();
                } else {
                    self.feed_char(value);
                }
            }
        }
    }

    pub fn resize_with_client(&mut self, size: Size, client: &mut impl TerminalSessionClient) {
        self.screen.resize(size);
        client.resized(size);
    }

    pub fn finish_input(&mut self) {
        for value in self.utf8.finish() {
            self.feed_char(value);
        }
    }

    fn feed_char(&mut self, value: char) {
        match &mut self.state {
            ParserState::Ground => self.feed_ground(value),
            ParserState::Escape => self.feed_escape(value),
            ParserState::Csi(buffer) => {
                if value.is_ascii_digit() || value == ';' || value == '?' {
                    buffer.push(value);
                } else {
                    let (private, params) = match buffer.strip_prefix('?') {
                        Some(params) => (true, parse_params(params)),
                        None => (false, parse_params(buffer)),
                    };
                    self.state = ParserState::Ground;
                    self.dispatch_csi(value, &params, private);
                }
            }
            ParserState::ControlString {
                kind,
                escaped,
                length,
            } => {
                let mut terminated = false;
                if *escaped {
                    if value == '\\' {
                        terminated = true;
                    } else {
                        *escaped = false;
                        *length += 1;
                    }
                } else if *kind == ControlStringKind::Osc && value == '\u{7}' {
                    terminated = true;
                } else if value == '\u{1b}' {
                    *escaped = true;
                } else {
                    *length += 1;
                }

                let discard = terminated || *length > MAX_CONTROL_STRING_LENGTH;
                if discard {
                    self.state = ParserState::Ground;
                }
            }
        }
    }

    fn feed_ground(&mut self, value: char) {
        if value == '\u{1b}' {
            self.state = ParserState::Escape;
        } else {
            self.screen.write_char(value, self.style);
        }
    }

    fn feed_escape(&mut self, value: char) {
        self.state = match value {
            '[' => ParserState::Csi(String::new()),
            ']' => ParserState::control_string(ControlStringKind::Osc),
            'P' => ParserState::control_string(ControlStringKind::Dcs),
            '_' => ParserState::control_string(ControlStringKind::Apc),
            _ => ParserState::Ground,
        };
    }

    fn dispatch_csi(&mut self, command: char, params: &[usize], private: bool) {
        match command {
            'A' => self.screen.move_cursor_by(0, -(amount(params) as isize)),
            'B' => self.screen.move_cursor_by(0, amount(params) as isize),
            'C' => self.screen.move_cursor_by(amount(params) as isize, 0),
            'D' => self.screen.move_cursor_by(-(amount(params) as isize), 0),
            'H' | 'f' => self.set_cursor_position(params),
            'J' => self.erase_display(params),
            'K' => self.erase_line(params),
            'm' => self.select_graphic_rendition(params),
            'h' if private => self.set_dec_private_mode(params, true),
            'l' if private => self.set_dec_private_mode(params, false),
            _ => {}
        }
    }

    fn set_dec_private_mode(&mut self, params: &[usize], enabled: bool) {
        for param in params {
            if *param == 7 {
                self.screen.set_auto_wrap(enabled);
            } else if matches!(*param, 47 | 1047 | 1049) {
                self.set_alternate_screen(enabled);
            }
        }
    }

    fn set_alternate_screen(&mut self, enabled: bool) {
        if enabled && self.saved_primary_screen.is_none() {
            let alternate = Screen::new(self.screen.size());
            self.saved_primary_screen = Some(std::mem::replace(&mut self.screen, alternate));
        } else if !enabled && let Some(primary) = self.saved_primary_screen.take() {
            self.screen = primary;
        }
    }

    fn set_cursor_position(&mut self, params: &[usize]) {
        let row = params.first().copied().unwrap_or(1).max(1) - 1;
        let column = params.get(1).copied().unwrap_or(1).max(1) - 1;
        self.screen.set_cursor(Position { column, row });
    }

    fn erase_display(&mut self, params: &[usize]) {
        if params.first().copied().unwrap_or(0) == 2 {
            self.screen.clear_all();
        }
    }

    fn erase_line(&mut self, params: &[usize]) {
        match params.first().copied().unwrap_or(0) {
            0 => self.screen.clear_row_from_cursor(),
            1 => self.screen.clear_row_to_cursor(),
            2 => self.screen.clear_row(),
            _ => {}
        }
    }

    fn select_graphic_rendition(&mut self, params: &[usize]) {
        let params = if params.is_empty() { &[0][..] } else { params };
        for param in params {
            match param {
                0 => self.style = Style::default(),
                1 => self.style.bold = true,
                3 => self.style.italic = true,
                4 => self.style.underline = true,
                7 => self.style.inverse = true,
                22 => self.style.bold = false,
                23 => self.style.italic = false,
                24 => self.style.underline = false,
                27 => self.style.inverse = false,
                _ => {}
            }
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum ParserState {
    Ground,
    Escape,
    Csi(String),
    ControlString {
        kind: ControlStringKind,
        escaped: bool,
        length: usize,
    },
}

impl ParserState {
    fn control_string(kind: ControlStringKind) -> Self {
        Self::ControlString {
            kind,
            escaped: false,
            length: 0,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ControlStringKind {
    Osc,
    Dcs,
    Apc,
}

fn parse_params(buffer: &str) -> Vec<usize> {
    if buffer.is_empty() {
        return Vec::new();
    }

    buffer
        .split(';')
        .map(|part| part.parse::<usize>().unwrap_or(0))
        .collect()
}

fn amount(params: &[usize]) -> usize {
    params.first().copied().unwrap_or(1).max(1)
}

#[cfg(test)]
mod tests {
    use super::Terminal;
    use crate::session::{TerminalOutput, TerminalSessionClient};
    use crate::terminal::{Position, Size, Style};

    #[derive(Default)]
    struct MockOutput {
        bells: usize,
    }

    impl TerminalOutput for MockOutput {
        fn write_to_process(&mut self, _bytes: &[u8]) {}

        fn title_changed(&mut self, _old_title: Option<&str>, _new_title: &str) {}

        fn copy_to_clipboard(&mut self, _text: &str) {}

        fn paste_from_clipboard(&mut self) {}

        fn bell(&mut self) {
            self.bells += 1;
        }

        fn colors_changed(&mut self) {}
    }

    #[derive(Default)]
    struct MockSessionClient {
        resized: Vec<Size>,
    }

    impl TerminalSessionClient for MockSessionClient {
        fn text_changed(&mut self) {}

        fn title_changed(&mut self) {}

        fn session_finished(&mut self) {}

        fn resized(&mut self, size: Size) {
            self.resized.push(size);
        }

        fn cursor_state_changed(&mut self, _visible: bool) {}
    }

    #[test]
    fn writes_printable_utf8_text() {
        let mut terminal = Terminal::new(Size::new(6, 2));

        terminal.feed_bytes("a枝".as_bytes());

        assert_eq!(Some("a枝    ".to_string()), terminal.screen().row_text(0));
    }

    #[test]
    fn notifies_output_and_session_clients() {
        let mut terminal = Terminal::new(Size::new(3, 2));
        let mut output = MockOutput::default();
        let mut client = MockSessionClient::default();

        terminal.feed_bytes_with_output(b"\x07", &mut output);
        terminal.resize_with_client(Size::new(4, 3), &mut client);

        assert_eq!(1, output.bells);
        assert_eq!(vec![Size::new(4, 3)], client.resized);
    }

    #[test]
    fn csi_moves_cursor_and_writes_at_position() {
        let mut terminal = Terminal::new(Size::new(5, 3));

        terminal.feed_bytes(b"\x1b[2;3HX");

        assert_eq!(Some("  X  ".to_string()), terminal.screen().row_text(1));
        assert_eq!(Position { column: 3, row: 1 }, terminal.screen().cursor());
    }

    #[test]
    fn csi_erases_line_from_cursor() {
        let mut terminal = Terminal::new(Size::new(5, 1));

        terminal.feed_bytes(b"abcde\x1b[1;3H\x1b[K");

        assert_eq!(Some("ab   ".to_string()), terminal.screen().row_text(0));
    }

    #[test]
    fn csi_sgr_updates_and_resets_style() {
        let mut terminal = Terminal::new(Size::new(5, 1));

        terminal.feed_bytes(b"\x1b[1;3;4;7m");

        assert_eq!(
            Style {
                bold: true,
                italic: true,
                underline: true,
                inverse: true,
            },
            terminal.style()
        );

        terminal.feed_bytes(b"\x1b[0m");

        assert_eq!(Style::default(), terminal.style());
    }

    #[test]
    fn unknown_csi_is_ignored_safely() {
        let mut terminal = Terminal::new(Size::new(5, 1));

        terminal.feed_bytes(b"a\x1b[999zb");

        assert_eq!(Some("ab   ".to_string()), terminal.screen().row_text(0));
    }

    #[test]
    fn consumes_osc_dcs_and_apc_strings() {
        let mut terminal = Terminal::new(Size::new(12, 1));

        terminal.feed_bytes(b"a\x1b]0;title\x07b\x1bPignored\x1b\\c\x1b_payload\x1b\\d");

        assert_eq!(
            Some("abcd        ".to_string()),
            terminal.screen().row_text(0)
        );
    }

    #[test]
    fn ignores_embedded_escape_in_apc_until_string_terminator() {
        let mut terminal = Terminal::new(Size::new(12, 1));

        terminal.feed_bytes(b"hello \x1b_some\x13\x1b_ignored\x1b\\ world");

        assert_eq!(
            Some("hello  world".to_string()),
            terminal.screen().row_text(0)
        );
    }

    #[test]
    fn decset_wraparound_mode_controls_right_edge_writes() {
        let mut terminal = Terminal::new(Size::new(3, 2));

        terminal.feed_bytes(b"\x1b[?7labcd");

        assert_eq!(Some("abd".to_string()), terminal.screen().row_text(0));
        assert_eq!(Some("   ".to_string()), terminal.screen().row_text(1));
    }

    #[test]
    fn decset_1049_restores_primary_screen() {
        let mut terminal = Terminal::new(Size::new(4, 2));

        terminal.feed_bytes(b"t\x1b[?1049h\x1b[Hest\r\nme");
        assert_eq!(Some("est ".to_string()), terminal.screen().row_text(0));
        assert_eq!(Some("me  ".to_string()), terminal.screen().row_text(1));

        terminal.feed_bytes(b"\x1b[?1049lry");
        assert_eq!(Some("try ".to_string()), terminal.screen().row_text(0));
        assert_eq!(Some("    ".to_string()), terminal.screen().row_text(1));
    }
}
