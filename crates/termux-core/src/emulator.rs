use crate::terminal::{Position, Screen, Size, Style};
use crate::utf8::Utf8Decoder;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Terminal {
    screen: Screen,
    style: Style,
    state: ParserState,
    utf8: Utf8Decoder,
    output: Vec<u8>,
}

impl Terminal {
    pub fn new(size: Size) -> Self {
        Self {
            screen: Screen::new(size),
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
                if value.is_ascii_digit() || value == ';' {
                    buffer.push(value);
                } else {
                    let params = parse_params(buffer);
                    self.state = ParserState::Ground;
                    self.dispatch_csi(value, &params);
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
        self.state = if value == '[' {
            ParserState::Csi(String::new())
        } else {
            ParserState::Ground
        };
    }

    fn dispatch_csi(&mut self, command: char, params: &[usize]) {
        match command {
            'A' => self.screen.move_cursor_by(0, -(amount(params) as isize)),
            'B' => self.screen.move_cursor_by(0, amount(params) as isize),
            'C' => self.screen.move_cursor_by(amount(params) as isize, 0),
            'D' => self.screen.move_cursor_by(-(amount(params) as isize), 0),
            'H' | 'f' => self.set_cursor_position(params),
            'J' => self.erase_display(params),
            'K' => self.erase_line(params),
            'm' => self.select_graphic_rendition(params),
            _ => {}
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
    use crate::terminal::{Position, Size, Style};

    #[test]
    fn writes_printable_utf8_text() {
        let mut terminal = Terminal::new(Size::new(6, 2));

        terminal.feed_bytes("a枝".as_bytes());

        assert_eq!(Some("a枝    ".to_string()), terminal.screen().row_text(0));
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
}
