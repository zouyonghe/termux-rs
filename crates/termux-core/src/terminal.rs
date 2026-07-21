use std::collections::VecDeque;

use crate::wcwidth;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Size {
    pub columns: usize,
    pub rows: usize,
}

impl Size {
    pub fn new(columns: usize, rows: usize) -> Self {
        assert!(columns > 0, "terminal columns must be greater than zero");
        assert!(rows > 0, "terminal rows must be greater than zero");
        Self { columns, rows }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Position {
    pub column: usize,
    pub row: usize,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Style {
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TextStyle(u64);

impl TextStyle {
    pub const BOLD: u16 = 1;
    pub const ITALIC: u16 = 1 << 1;
    pub const UNDERLINE: u16 = 1 << 2;
    pub const BLINK: u16 = 1 << 3;
    pub const INVERSE: u16 = 1 << 4;
    pub const INVISIBLE: u16 = 1 << 5;
    pub const STRIKETHROUGH: u16 = 1 << 6;
    pub const PROTECTED: u16 = 1 << 7;
    pub const DIM: u16 = 1 << 8;
    pub const TRUE_COLOR_FLAG: u32 = 0xff00_0000;

    const TRUE_FOREGROUND: u64 = 1 << 9;
    const TRUE_BACKGROUND: u64 = 1 << 10;

    pub fn encode(foreground: u32, background: u32, effects: u16) -> Self {
        let mut value = u64::from(effects & 0x1ff);
        value |= if foreground & Self::TRUE_COLOR_FLAG == Self::TRUE_COLOR_FLAG {
            Self::TRUE_FOREGROUND | (u64::from(foreground & 0x00ff_ffff) << 40)
        } else {
            u64::from(foreground & 0x1ff) << 40
        };
        value |= if background & Self::TRUE_COLOR_FLAG == Self::TRUE_COLOR_FLAG {
            Self::TRUE_BACKGROUND | (u64::from(background & 0x00ff_ffff) << 16)
        } else {
            u64::from(background & 0x1ff) << 16
        };
        Self(value)
    }

    pub fn foreground(self) -> u32 {
        let color = (self.0 >> 40) as u32 & 0x00ff_ffff;
        if self.0 & Self::TRUE_FOREGROUND != 0 {
            Self::TRUE_COLOR_FLAG | color
        } else {
            color & 0x1ff
        }
    }

    pub fn background(self) -> u32 {
        let color = (self.0 >> 16) as u32 & 0x00ff_ffff;
        if self.0 & Self::TRUE_BACKGROUND != 0 {
            Self::TRUE_COLOR_FLAG | color
        } else {
            color & 0x1ff
        }
    }

    pub fn effects(self) -> u16 {
        self.0 as u16 & 0x1ff
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Cell {
    pub value: char,
    pub style: Style,
    combining: String,
    width: usize,
    continuation: bool,
}

impl Default for Cell {
    fn default() -> Self {
        Self {
            value: ' ',
            style: Style::default(),
            combining: String::new(),
            width: 1,
            continuation: false,
        }
    }
}

impl Cell {
    fn new(value: char, style: Style, width: usize) -> Self {
        Self {
            value,
            style,
            combining: String::new(),
            width,
            continuation: false,
        }
    }

    fn continuation(style: Style) -> Self {
        Self {
            style,
            continuation: true,
            ..Self::default()
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct Row {
    cells: Vec<Cell>,
    wrapped: bool,
}

impl Row {
    fn blank(columns: usize) -> Self {
        Self {
            cells: vec![Cell::default(); columns],
            wrapped: false,
        }
    }

    fn clear(&mut self) {
        self.cells.fill(Cell::default());
        self.wrapped = false;
    }

    fn text(&self) -> String {
        self.cells
            .iter()
            .flat_map(|cell| [cell.value.to_string(), cell.combining.clone()])
            .collect()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Screen {
    size: Size,
    cursor: Position,
    auto_wrap: bool,
    pending_wrap: bool,
    scrollback: VecDeque<Row>,
    scrollback_limit: usize,
    rows: Vec<Row>,
}

impl Screen {
    pub fn new(size: Size) -> Self {
        Self::new_with_scrollback(size, 10_000)
    }

    pub fn new_with_scrollback(size: Size, scrollback_limit: usize) -> Self {
        Self {
            size,
            cursor: Position::default(),
            auto_wrap: true,
            pending_wrap: false,
            scrollback: VecDeque::with_capacity(scrollback_limit),
            scrollback_limit,
            rows: vec![Row::blank(size.columns); size.rows],
        }
    }

    pub fn size(&self) -> Size {
        self.size
    }

    pub fn cursor(&self) -> Position {
        self.cursor
    }

    pub fn set_cursor(&mut self, position: Position) {
        self.cursor.column = position.column.min(self.size.columns - 1);
        self.cursor.row = position.row.min(self.size.rows - 1);
        self.pending_wrap = false;
    }

    pub fn move_cursor_by(&mut self, columns: isize, rows: isize) {
        self.set_cursor(Position {
            column: self.cursor.column.saturating_add_signed(columns),
            row: self.cursor.row.saturating_add_signed(rows),
        });
    }

    pub fn set_auto_wrap(&mut self, enabled: bool) {
        self.auto_wrap = enabled;
        if !enabled {
            self.pending_wrap = false;
        }
    }

    pub fn resize(&mut self, size: Size) {
        let mut rows = vec![Row::blank(size.columns); size.rows];
        for row in 0..self.size.rows.min(size.rows) {
            for column in 0..self.size.columns.min(size.columns) {
                rows[row].cells[column] = self.rows[row].cells[column].clone();
            }
            rows[row].wrapped = self.rows[row].wrapped && size.columns == self.size.columns;
        }
        self.rows = rows;
        self.size = size;
        self.cursor.column = self.cursor.column.min(size.columns - 1);
        self.cursor.row = self.cursor.row.min(size.rows - 1);
        self.pending_wrap = false;
    }

    pub fn cell(&self, position: Position) -> Option<Cell> {
        self.rows
            .get(position.row)
            .and_then(|row| row.cells.get(position.column))
            .cloned()
    }

    pub fn write_char(&mut self, value: char, style: Style) {
        match value {
            '\r' => {
                self.cursor.column = 0;
                self.pending_wrap = false;
            }
            '\n' => {
                self.line_feed();
                self.pending_wrap = false;
            }
            '\x08' => {
                self.cursor.column = self.cursor.column.saturating_sub(1);
                self.pending_wrap = false;
            }
            _ => self.write_printable(value, style),
        }
    }

    pub fn write_str(&mut self, text: &str, style: Style) {
        for value in text.chars() {
            self.write_char(value, style);
        }
    }

    pub fn clear_all(&mut self) {
        for row in &mut self.rows {
            row.clear();
        }
        self.pending_wrap = false;
    }

    pub fn clear_row_from_cursor(&mut self) {
        self.clear_row_range(self.cursor.row, self.cursor.column, self.size.columns);
        self.pending_wrap = false;
    }

    pub fn clear_row_to_cursor(&mut self) {
        self.clear_row_range(self.cursor.row, 0, self.cursor.column + 1);
        self.pending_wrap = false;
    }

    pub fn clear_row(&mut self) {
        self.clear_row_range(self.cursor.row, 0, self.size.columns);
        self.pending_wrap = false;
    }

    pub fn row_text(&self, row: usize) -> Option<String> {
        self.rows.get(row).map(Row::text)
    }

    pub fn scrollback_len(&self) -> usize {
        self.scrollback.len()
    }

    pub fn scrollback_row(&self, row: usize) -> Option<String> {
        self.scrollback.get(row).map(Row::text)
    }

    pub fn row_wrapped(&self, row: usize) -> bool {
        self.rows.get(row).is_some_and(|row| row.wrapped)
    }

    fn write_printable(&mut self, value: char, style: Style) {
        let width = wcwidth::width(value);
        if width == 0 {
            self.write_combining(value);
            return;
        }

        let width = width.min(self.size.columns);
        if self.pending_wrap && !self.auto_wrap {
            self.pending_wrap = false;
        }
        if self.pending_wrap || self.cursor.column + width > self.size.columns {
            self.rows[self.cursor.row].wrapped = true;
            self.cursor.column = 0;
            self.line_feed();
            self.pending_wrap = false;
        }

        self.clear_cell_at_cursor();
        self.rows[self.cursor.row].cells[self.cursor.column] = Cell::new(value, style, width);
        if width == 2 {
            self.rows[self.cursor.row].cells[self.cursor.column + 1] = Cell::continuation(style);
        }

        if self.cursor.column + width < self.size.columns {
            self.cursor.column += width;
        } else if self.auto_wrap {
            self.cursor.column = self.size.columns - 1;
            self.pending_wrap = true;
        }
    }

    fn write_combining(&mut self, value: char) {
        let mut column = if self.pending_wrap {
            self.cursor.column
        } else {
            self.cursor.column.saturating_sub(1)
        };
        if self.rows[self.cursor.row].cells[column].continuation && column > 0 {
            column -= 1;
        }
        self.rows[self.cursor.row].cells[column]
            .combining
            .push(value);
    }

    fn clear_cell_at_cursor(&mut self) {
        let row = &mut self.rows[self.cursor.row];
        let column = self.cursor.column;
        if row.cells[column].continuation && column > 0 {
            row.cells[column - 1] = Cell::default();
        }
        if row.cells[column].width == 2 && column + 1 < self.size.columns {
            row.cells[column + 1] = Cell::default();
        }
        row.cells[column] = Cell::default();
    }

    fn line_feed(&mut self) {
        if self.cursor.row + 1 < self.size.rows {
            self.cursor.row += 1;
            return;
        }

        let row = self.rows.remove(0);
        if self.scrollback_limit > 0 {
            if self.scrollback.len() == self.scrollback_limit {
                self.scrollback.pop_front();
            }
            self.scrollback.push_back(row);
        }
        self.rows.push(Row::blank(self.size.columns));
    }

    fn clear_row_range(&mut self, row: usize, start_column: usize, end_column: usize) {
        if let Some(row) = self.rows.get_mut(row) {
            let start = start_column.min(self.size.columns);
            let end = end_column.min(self.size.columns);
            row.cells[start..end].fill(Cell::default());
            row.wrapped = false;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Position, Screen, Size, Style, TextStyle};

    #[test]
    fn writes_text_and_moves_cursor() {
        let mut screen = Screen::new(Size::new(5, 2));
        screen.write_str("abc", Style::default());
        assert_eq!(Some("abc  ".to_string()), screen.row_text(0));
        assert_eq!(Position { column: 3, row: 0 }, screen.cursor());
    }

    #[test]
    fn wraps_at_right_edge() {
        let mut screen = Screen::new(Size::new(3, 2));
        screen.write_str("abcd", Style::default());
        assert_eq!(Some("abc".to_string()), screen.row_text(0));
        assert_eq!(Some("d  ".to_string()), screen.row_text(1));
        assert_eq!(Position { column: 1, row: 1 }, screen.cursor());
    }

    #[test]
    fn resize_preserves_visible_cells_and_clamps_cursor() {
        let mut screen = Screen::new(Size::new(4, 2));
        screen.write_str("abcdef", Style::default());
        screen.resize(Size::new(3, 1));
        assert_eq!(Size::new(3, 1), screen.size());
        assert_eq!(Some("abc".to_string()), screen.row_text(0));
        assert_eq!(Position { column: 2, row: 0 }, screen.cursor());
    }

    #[test]
    fn cell_returns_none_outside_bounds() {
        let screen = Screen::new(Size::new(2, 2));
        assert_eq!(None, screen.cell(Position { column: 2, row: 0 }));
        assert_eq!(None, screen.cell(Position { column: 0, row: 2 }));
    }

    #[test]
    fn scrolls_visible_rows_into_transcript() {
        let mut screen = Screen::new_with_scrollback(Size::new(2, 2), 2);
        screen.write_str("abcde", Style::default());
        assert_eq!(1, screen.scrollback_len());
        assert_eq!(Some("ab".to_string()), screen.scrollback_row(0));
        assert_eq!(Some("cd".to_string()), screen.row_text(0));
        assert_eq!(Some("e ".to_string()), screen.row_text(1));
    }

    #[test]
    fn marks_row_as_wrapped_at_right_edge() {
        let mut screen = Screen::new(Size::new(3, 2));
        screen.write_str("abcd", Style::default());
        assert!(screen.row_wrapped(0));
        assert!(!screen.row_wrapped(1));
    }

    #[test]
    fn text_style_round_trips_termux_attributes_and_colors() {
        let style = TextStyle::encode(
            TextStyle::TRUE_COLOR_FLAG | 0x12_34_56,
            TextStyle::TRUE_COLOR_FLAG | 0x65_43_21,
            TextStyle::BOLD | TextStyle::UNDERLINE | TextStyle::PROTECTED,
        );
        assert_eq!(TextStyle::TRUE_COLOR_FLAG | 0x12_34_56, style.foreground());
        assert_eq!(TextStyle::TRUE_COLOR_FLAG | 0x65_43_21, style.background());
        assert_eq!(
            TextStyle::BOLD | TextStyle::UNDERLINE | TextStyle::PROTECTED,
            style.effects()
        );
    }

    #[test]
    fn advances_cursor_by_unicode_cell_width() {
        let mut screen = Screen::new(Size::new(6, 1));

        screen.write_str("a枝b", Style::default());

        assert_eq!(Some("a枝 b  ".to_string()), screen.row_text(0));
        assert_eq!(Position { column: 4, row: 0 }, screen.cursor());
    }

    #[test]
    fn attaches_combining_marks_without_advancing_cursor() {
        let mut screen = Screen::new(Size::new(5, 1));

        screen.write_str("n\u{0302}", Style::default());

        assert_eq!(Some("n\u{0302}    ".to_string()), screen.row_text(0));
        assert_eq!(Position { column: 1, row: 0 }, screen.cursor());
    }

    #[test]
    fn clearing_a_full_row_cancels_pending_wrap() {
        let mut screen = Screen::new(Size::new(3, 1));

        screen.write_str("abc", Style::default());
        screen.clear_row_from_cursor();
        screen.write_str("d", Style::default());

        assert_eq!(Some("abd".to_string()), screen.row_text(0));
    }
}
