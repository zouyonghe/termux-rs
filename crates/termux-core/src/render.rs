use crate::terminal::{Position, Size, Style};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RenderSnapshot {
    pub version: u64,
    pub size: Size,
    pub cursor: Position,
    pub rows: Vec<RenderRow>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RenderRow {
    pub cells: Vec<RenderCell>,
    pub wrapped: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RenderCell {
    pub text: Option<String>,
    pub style: Style,
    pub width: usize,
    pub continuation: bool,
}
