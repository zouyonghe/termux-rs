#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Key {
    Char(char),
    Up,
    Down,
    Right,
    Left,
    Home,
    End,
    Insert,
    Delete,
    PageUp,
    PageDown,
    Escape,
    Backspace,
    Tab,
    Enter,
    Function(u8),
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Modifiers {
    pub shift: bool,
    pub alt: bool,
    pub ctrl: bool,
    pub num_lock: bool,
}

impl Modifiers {
    fn legacy_value(self) -> usize {
        1 + usize::from(self.shift) + usize::from(self.alt) * 2 + usize::from(self.ctrl) * 4
    }

    fn kitty_value(self) -> usize {
        self.legacy_value() + usize::from(self.num_lock) * 128
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KeyEventKind {
    Press,
    Repeat,
    Release,
}

impl KeyEventKind {
    fn kitty_value(self) -> usize {
        match self {
            Self::Press => 1,
            Self::Repeat => 2,
            Self::Release => 3,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct KeyEvent {
    pub key: Key,
    pub modifiers: Modifiers,
    pub kind: KeyEventKind,
}

impl KeyEvent {
    pub fn press(key: Key, modifiers: Modifiers) -> Self {
        Self {
            key,
            modifiers,
            kind: KeyEventKind::Press,
        }
    }
}

pub fn encode_legacy(event: KeyEvent) -> Option<String> {
    if event.kind == KeyEventKind::Release {
        return None;
    }

    let modifiers = event.modifiers;
    match event.key {
        Key::Char(value) => encode_legacy_char(value, modifiers),
        Key::Up => Some(cursor_key('A', modifiers)),
        Key::Down => Some(cursor_key('B', modifiers)),
        Key::Right => Some(cursor_key('C', modifiers)),
        Key::Left => Some(cursor_key('D', modifiers)),
        Key::Home => Some(cursor_key('H', modifiers)),
        Key::End => Some(cursor_key('F', modifiers)),
        Key::Insert => Some(numbered_key(2, modifiers)),
        Key::Delete => Some(numbered_key(3, modifiers)),
        Key::PageUp => Some(numbered_key(5, modifiers)),
        Key::PageDown => Some(numbered_key(6, modifiers)),
        Key::Escape => Some("\u{1b}".to_string()),
        Key::Backspace => Some(if modifiers.ctrl { "\u{8}" } else { "\u{7f}" }.to_string()),
        Key::Tab => Some(if modifiers.shift { "\u{1b}[Z" } else { "\t" }.to_string()),
        Key::Enter => Some(if modifiers.alt { "\u{1b}\r" } else { "\r" }.to_string()),
        Key::Function(number) => encode_function_key(number, modifiers),
    }
}

fn encode_legacy_char(value: char, modifiers: Modifiers) -> Option<String> {
    let mut value = value;
    if modifiers.ctrl {
        value = match value {
            'a'..='z' => ((value as u8) - b'a' + 1) as char,
            'A'..='Z' => ((value as u8) - b'A' + 1) as char,
            ' ' | '2' => '\0',
            '[' | '3' => '\u{1b}',
            '\\' | '4' => '\u{1c}',
            ']' | '5' => '\u{1d}',
            '^' | '6' => '\u{1e}',
            '_' | '7' | '/' => '\u{1f}',
            '8' => '\u{7f}',
            _ => value,
        };
    }

    Some(if modifiers.alt {
        format!("\u{1b}{value}")
    } else {
        value.to_string()
    })
}

fn cursor_key(final_byte: char, modifiers: Modifiers) -> String {
    if modifiers == Modifiers::default() {
        format!("\u{1b}[{final_byte}")
    } else {
        format!("\u{1b}[1;{}{}", modifiers.legacy_value(), final_byte)
    }
}

fn numbered_key(number: usize, modifiers: Modifiers) -> String {
    if modifiers == Modifiers::default() {
        format!("\u{1b}[{number}~")
    } else {
        format!("\u{1b}[{number};{}~", modifiers.legacy_value())
    }
}

fn encode_function_key(number: u8, modifiers: Modifiers) -> Option<String> {
    let final_byte = match number {
        1 => {
            return Some(if modifiers == Modifiers::default() {
                "\u{1b}OP".to_string()
            } else {
                cursor_key('P', modifiers)
            });
        }
        2 => {
            return Some(if modifiers == Modifiers::default() {
                "\u{1b}OQ".to_string()
            } else {
                cursor_key('Q', modifiers)
            });
        }
        3 => {
            return Some(if modifiers == Modifiers::default() {
                "\u{1b}OR".to_string()
            } else {
                cursor_key('R', modifiers)
            });
        }
        4 => {
            return Some(if modifiers == Modifiers::default() {
                "\u{1b}OS".to_string()
            } else {
                cursor_key('S', modifiers)
            });
        }
        5 => 15,
        6 => 17,
        7 => 18,
        8 => 19,
        9 => 20,
        10 => 21,
        11 => 23,
        12 => 24,
        _ => return None,
    };
    Some(numbered_key(final_byte, modifiers))
}

pub const KITTY_DISAMBIGUATE: u8 = 1;
pub const KITTY_REPORT_EVENTS: u8 = 1 << 1;
pub const KITTY_REPORT_ALTERNATES: u8 = 1 << 2;
pub const KITTY_REPORT_ALL_KEYS: u8 = 1 << 3;
pub const KITTY_REPORT_TEXT: u8 = 1 << 4;
pub const KITTY_SUPPORTED_FLAGS: u8 = KITTY_DISAMBIGUATE
    | KITTY_REPORT_EVENTS
    | KITTY_REPORT_ALTERNATES
    | KITTY_REPORT_ALL_KEYS
    | KITTY_REPORT_TEXT;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct KittyKeyboardMode {
    flags: u8,
    stack: Vec<u8>,
}

impl KittyKeyboardMode {
    pub fn flags(&self) -> u8 {
        self.flags
    }

    pub fn set(&mut self, flags: u8, mode: KittySetMode) {
        let flags = flags & KITTY_SUPPORTED_FLAGS;
        self.flags = match mode {
            KittySetMode::Replace => flags,
            KittySetMode::Set => self.flags | flags,
            KittySetMode::Reset => self.flags & !flags,
        };
    }

    pub fn push(&mut self, flags: u8) {
        if self.stack.len() == 20 {
            self.stack.remove(0);
        }
        self.stack.push(self.flags);
        self.flags = flags & KITTY_SUPPORTED_FLAGS;
    }

    pub fn pop(&mut self, count: usize) {
        let count = count.max(1);
        let mut flags = 0;
        for _ in 0..count {
            match self.stack.pop() {
                Some(value) => flags = value,
                None => {
                    flags = 0;
                    break;
                }
            }
        }
        self.flags = flags;
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KittySetMode {
    Replace,
    Set,
    Reset,
}

pub fn encode_kitty(event: KeyEvent, flags: u8) -> Option<String> {
    let key = kitty_key(event.key)?;
    let mut modifier = event.modifiers.kitty_value().to_string();
    if flags & KITTY_REPORT_EVENTS != 0 {
        modifier.push(':');
        modifier.push_str(&event.kind.kitty_value().to_string());
    }

    match key {
        KittyKey::Final { number, final_byte } => {
            Some(format!("\u{1b}[{number};{modifier}{final_byte}"))
        }
        KittyKey::CodePoint(codepoint) => {
            let base = codepoint.to_ascii_lowercase();
            let mut key = (base as u32).to_string();
            if flags & KITTY_REPORT_ALTERNATES != 0 && base != codepoint {
                key.push(':');
                key.push_str(&(codepoint as u32).to_string());
            }

            if flags & KITTY_REPORT_TEXT != 0
                && flags & KITTY_REPORT_ALL_KEYS != 0
                && codepoint >= ' '
                && !(0x80..=0x9f).contains(&(codepoint as u32))
            {
                Some(format!("\u{1b}[{};{};{}u", key, modifier, codepoint as u32))
            } else {
                Some(format!("\u{1b}[{key};{modifier}u"))
            }
        }
    }
}

enum KittyKey {
    Final { number: usize, final_byte: char },
    CodePoint(char),
}

fn kitty_key(key: Key) -> Option<KittyKey> {
    match key {
        Key::Char(value) => Some(KittyKey::CodePoint(value)),
        Key::Up => Some(KittyKey::Final {
            number: 1,
            final_byte: 'A',
        }),
        Key::Down => Some(KittyKey::Final {
            number: 1,
            final_byte: 'B',
        }),
        Key::Right => Some(KittyKey::Final {
            number: 1,
            final_byte: 'C',
        }),
        Key::Left => Some(KittyKey::Final {
            number: 1,
            final_byte: 'D',
        }),
        Key::Home => Some(KittyKey::Final {
            number: 1,
            final_byte: 'H',
        }),
        Key::End => Some(KittyKey::Final {
            number: 1,
            final_byte: 'F',
        }),
        Key::Insert => Some(KittyKey::Final {
            number: 2,
            final_byte: '~',
        }),
        Key::Delete => Some(KittyKey::Final {
            number: 3,
            final_byte: '~',
        }),
        Key::PageUp => Some(KittyKey::Final {
            number: 5,
            final_byte: '~',
        }),
        Key::PageDown => Some(KittyKey::Final {
            number: 6,
            final_byte: '~',
        }),
        Key::Escape => Some(KittyKey::CodePoint('\u{1b}')),
        Key::Backspace => Some(KittyKey::CodePoint('\u{7f}')),
        Key::Tab => Some(KittyKey::CodePoint('\t')),
        Key::Enter => Some(KittyKey::CodePoint('\r')),
        Key::Function(number) => match number {
            1 => Some(KittyKey::Final {
                number: 1,
                final_byte: 'P',
            }),
            2 => Some(KittyKey::Final {
                number: 1,
                final_byte: 'Q',
            }),
            3 => Some(KittyKey::Final {
                number: 1,
                final_byte: 'R',
            }),
            4 => Some(KittyKey::Final {
                number: 1,
                final_byte: 'S',
            }),
            5 => Some(KittyKey::Final {
                number: 15,
                final_byte: '~',
            }),
            6 => Some(KittyKey::Final {
                number: 17,
                final_byte: '~',
            }),
            7 => Some(KittyKey::Final {
                number: 18,
                final_byte: '~',
            }),
            8 => Some(KittyKey::Final {
                number: 19,
                final_byte: '~',
            }),
            9 => Some(KittyKey::Final {
                number: 20,
                final_byte: '~',
            }),
            10 => Some(KittyKey::Final {
                number: 21,
                final_byte: '~',
            }),
            11 => Some(KittyKey::Final {
                number: 23,
                final_byte: '~',
            }),
            12 => Some(KittyKey::Final {
                number: 24,
                final_byte: '~',
            }),
            _ => None,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::{
        KITTY_REPORT_ALL_KEYS, KITTY_REPORT_ALTERNATES, KITTY_REPORT_EVENTS, KITTY_REPORT_TEXT,
        Key, KeyEvent, KeyEventKind, KittyKeyboardMode, KittySetMode, Modifiers, encode_kitty,
        encode_legacy,
    };

    #[test]
    fn legacy_arrow_with_ctrl_matches_xterm_modifier_form() {
        let event = KeyEvent::press(
            Key::Up,
            Modifiers {
                ctrl: true,
                ..Modifiers::default()
            },
        );

        assert_eq!(Some("\u{1b}[1;5A".to_string()), encode_legacy(event));
    }

    #[test]
    fn legacy_ctrl_c_emits_control_code() {
        let event = KeyEvent::press(
            Key::Char('c'),
            Modifiers {
                ctrl: true,
                ..Modifiers::default()
            },
        );

        assert_eq!(Some("\u{3}".to_string()), encode_legacy(event));
    }

    #[test]
    fn legacy_release_events_are_ignored() {
        let event = KeyEvent {
            key: Key::Enter,
            modifiers: Modifiers::default(),
            kind: KeyEventKind::Release,
        };

        assert_eq!(None, encode_legacy(event));
    }

    #[test]
    fn kitty_mode_masks_and_stacks_flags() {
        let mut mode = KittyKeyboardMode::default();

        mode.set(0xff, KittySetMode::Replace);
        assert_eq!(31, mode.flags());

        mode.push(1);
        mode.set(KITTY_REPORT_EVENTS, KittySetMode::Set);
        assert_eq!(3, mode.flags());

        mode.pop(1);
        assert_eq!(31, mode.flags());
    }

    #[test]
    fn kitty_ctrl_c_disambiguates_control_key() {
        let event = KeyEvent::press(
            Key::Char('c'),
            Modifiers {
                ctrl: true,
                ..Modifiers::default()
            },
        );

        assert_eq!(Some("\u{1b}[99;5u".to_string()), encode_kitty(event, 0));
    }

    #[test]
    fn kitty_reports_repeat_event() {
        let event = KeyEvent {
            key: Key::Up,
            modifiers: Modifiers::default(),
            kind: KeyEventKind::Repeat,
        };

        assert_eq!(
            Some("\u{1b}[1;1:2A".to_string()),
            encode_kitty(event, KITTY_REPORT_EVENTS)
        );
    }

    #[test]
    fn kitty_reports_alternate_and_associated_text() {
        let event = KeyEvent::press(
            Key::Char('A'),
            Modifiers {
                shift: true,
                ..Modifiers::default()
            },
        );

        assert_eq!(
            Some("\u{1b}[97:65;2;65u".to_string()),
            encode_kitty(
                event,
                KITTY_REPORT_ALTERNATES | KITTY_REPORT_ALL_KEYS | KITTY_REPORT_TEXT
            )
        );
    }
}
