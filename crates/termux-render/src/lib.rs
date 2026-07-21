use termux_core::keyboard::{Key, KeyEvent, KeyEventKind, Modifiers};
use termux_core::terminal::Size;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CellPosition {
    pub column: usize,
    pub row: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Selection {
    pub anchor: CellPosition,
    pub focus: CellPosition,
}

impl Selection {
    pub fn new(anchor: CellPosition, focus: CellPosition, size: Size) -> Option<Self> {
        let in_bounds =
            |position: CellPosition| position.column < size.columns && position.row < size.rows;
        (in_bounds(anchor) && in_bounds(focus)).then_some(Self { anchor, focus })
    }

    pub fn bounds(self) -> (CellPosition, CellPosition) {
        (
            CellPosition {
                column: self.anchor.column.min(self.focus.column),
                row: self.anchor.row.min(self.focus.row),
            },
            CellPosition {
                column: self.anchor.column.max(self.focus.column),
                row: self.anchor.row.max(self.focus.row),
            },
        )
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct VirtualModifiers {
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
    pub function: bool,
}

impl VirtualModifiers {
    fn keyboard(self) -> Modifiers {
        Modifiers {
            ctrl: self.ctrl,
            alt: self.alt,
            shift: self.shift,
            ..Modifiers::default()
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VirtualModifier {
    Ctrl,
    Alt,
    Shift,
    Function,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExtraKey {
    Key(Key),
    Text(String),
    Modifier(VirtualModifier),
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct InputTranslator {
    modifiers: VirtualModifiers,
}

impl InputTranslator {
    pub fn modifiers(&self) -> VirtualModifiers {
        self.modifiers
    }

    pub fn set_modifier(&mut self, modifier: VirtualModifier, pressed: bool) {
        match modifier {
            VirtualModifier::Ctrl => self.modifiers.ctrl = pressed,
            VirtualModifier::Alt => self.modifiers.alt = pressed,
            VirtualModifier::Shift => self.modifiers.shift = pressed,
            VirtualModifier::Function => self.modifiers.function = pressed,
        }
    }

    pub fn translate_extra_key(&mut self, key: ExtraKey) -> Vec<KeyEvent> {
        match key {
            ExtraKey::Key(key) => self.translate_key(key),
            ExtraKey::Text(text) => text
                .chars()
                .flat_map(|value| self.translate_key(Key::Char(value)))
                .collect(),
            ExtraKey::Modifier(modifier) => {
                self.set_modifier(modifier, !self.modifier_pressed(modifier));
                Vec::new()
            }
        }
    }

    pub fn translate_key(&self, key: Key) -> Vec<KeyEvent> {
        let function_alt =
            self.modifiers.function && matches!(key, Key::Char('b' | 'B' | 'f' | 'F' | 'x' | 'X'));
        let key = self.translate_function_key(key);
        key.map(|key| KeyEvent {
            key,
            modifiers: Modifiers {
                alt: self.modifiers.alt || function_alt,
                ..self.modifiers.keyboard()
            },
            kind: KeyEventKind::Press,
        })
        .into_iter()
        .collect()
    }

    fn modifier_pressed(&self, modifier: VirtualModifier) -> bool {
        match modifier {
            VirtualModifier::Ctrl => self.modifiers.ctrl,
            VirtualModifier::Alt => self.modifiers.alt,
            VirtualModifier::Shift => self.modifiers.shift,
            VirtualModifier::Function => self.modifiers.function,
        }
    }

    fn translate_function_key(&self, key: Key) -> Option<Key> {
        if !self.modifiers.function {
            return Some(key);
        }

        Some(match key {
            Key::Char('w' | 'W') => Key::Up,
            Key::Char('a' | 'A') => Key::Left,
            Key::Char('s' | 'S') => Key::Down,
            Key::Char('d' | 'D') => Key::Right,
            Key::Char('p' | 'P') => Key::PageUp,
            Key::Char('n' | 'N') => Key::PageDown,
            Key::Char('t' | 'T') => Key::Tab,
            Key::Char('i' | 'I') => Key::Insert,
            Key::Char('h' | 'H') => Key::Char('~'),
            Key::Char('u' | 'U') => Key::Char('_'),
            Key::Char('l' | 'L') => Key::Char('|'),
            Key::Char('1'..='9') => Key::Function(match key {
                Key::Char(value) => value as u8 - b'0',
                _ => unreachable!(),
            }),
            Key::Char('0') => Key::Function(10),
            Key::Char('e' | 'E') => Key::Escape,
            Key::Char('.') => Key::Char('\u{1c}'),
            Key::Char('b' | 'B') => return Some(Key::Char('b')),
            Key::Char('f' | 'F') => return Some(Key::Char('f')),
            Key::Char('x' | 'X') => return Some(Key::Char('x')),
            Key::Char('v' | 'V' | 'q' | 'Q' | 'k' | 'K') => return None,
            key => key,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selection_rejects_positions_outside_terminal_bounds() {
        let size = Size::new(3, 2);
        let selection = Selection::new(
            CellPosition { column: 2, row: 1 },
            CellPosition { column: 0, row: 0 },
            size,
        )
        .unwrap();

        assert_eq!(
            (
                CellPosition { column: 0, row: 0 },
                CellPosition { column: 2, row: 1 }
            ),
            selection.bounds()
        );
        assert!(
            Selection::new(
                CellPosition { column: 3, row: 0 },
                CellPosition { column: 0, row: 0 },
                size
            )
            .is_none()
        );
        assert!(
            Selection::new(
                CellPosition { column: 0, row: 2 },
                CellPosition { column: 0, row: 0 },
                size
            )
            .is_none()
        );
    }

    #[test]
    fn extra_keys_apply_virtual_modifiers_without_encoding_protocol_bytes() {
        let mut translator = InputTranslator::default();
        assert!(
            translator
                .translate_extra_key(ExtraKey::Modifier(VirtualModifier::Ctrl))
                .is_empty()
        );
        assert!(
            translator
                .translate_extra_key(ExtraKey::Modifier(VirtualModifier::Alt))
                .is_empty()
        );

        assert_eq!(
            vec![KeyEvent::press(
                Key::Char('c'),
                Modifiers {
                    ctrl: true,
                    alt: true,
                    ..Modifiers::default()
                }
            )],
            translator.translate_extra_key(ExtraKey::Text("c".to_string()))
        );
    }

    #[test]
    fn function_modifier_translates_termux_virtual_keys() {
        let mut translator = InputTranslator::default();
        translator.set_modifier(VirtualModifier::Function, true);

        assert_eq!(
            vec![KeyEvent::press(Key::Up, Modifiers::default())],
            translator.translate_key(Key::Char('w'))
        );
        assert_eq!(
            vec![KeyEvent::press(Key::Function(10), Modifiers::default())],
            translator.translate_key(Key::Char('0'))
        );
        assert_eq!(
            vec![KeyEvent::press(Key::Char('|'), Modifiers::default())],
            translator.translate_key(Key::Char('l'))
        );
        assert_eq!(
            vec![KeyEvent::press(
                Key::Char('b'),
                Modifiers {
                    alt: true,
                    ..Modifiers::default()
                }
            )],
            translator.translate_key(Key::Char('b'))
        );
        assert_eq!(
            vec![KeyEvent::press(
                Key::Char('b'),
                Modifiers {
                    alt: true,
                    ..Modifiers::default()
                }
            )],
            translator.translate_key(Key::Char('B'))
        );
        assert!(translator.translate_key(Key::Char('v')).is_empty());
    }
}
