const REPLACEMENT: char = '\u{FFFD}';

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Utf8Decoder {
    bytes: [u8; 4],
    len: usize,
    expected: usize,
}

impl Utf8Decoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn accept(&mut self, byte: u8) -> Vec<char> {
        if self.expected == 0 {
            return self.start(byte);
        }

        if is_continuation(byte) {
            self.bytes[self.len] = byte;
            self.len += 1;

            if self.len == self.expected {
                return vec![self.finish_sequence()];
            }
            return Vec::new();
        }

        self.reset();
        let mut output = vec![REPLACEMENT];
        output.extend(self.start(byte));
        output
    }

    pub fn finish(&mut self) -> Vec<char> {
        if self.expected == 0 {
            return Vec::new();
        }

        self.reset();
        vec![REPLACEMENT]
    }

    fn start(&mut self, byte: u8) -> Vec<char> {
        match byte {
            0x00..=0x7f => vec![byte as char],
            0xc2..=0xdf => self.start_sequence(byte, 2),
            0xe0..=0xef => self.start_sequence(byte, 3),
            0xf0..=0xf4 => self.start_sequence(byte, 4),
            _ => vec![REPLACEMENT],
        }
    }

    fn start_sequence(&mut self, byte: u8, expected: usize) -> Vec<char> {
        self.bytes[0] = byte;
        self.len = 1;
        self.expected = expected;
        Vec::new()
    }

    fn finish_sequence(&mut self) -> char {
        let bytes = &self.bytes[..self.expected];
        let decoded = std::str::from_utf8(bytes)
            .ok()
            .and_then(|text| text.chars().next())
            .unwrap_or(REPLACEMENT);
        self.reset();
        decoded
    }

    fn reset(&mut self) {
        self.len = 0;
        self.expected = 0;
    }
}

fn is_continuation(byte: u8) -> bool {
    (byte & 0b1100_0000) == 0b1000_0000
}

#[cfg(test)]
mod tests {
    use super::{REPLACEMENT, Utf8Decoder};

    #[test]
    fn decodes_ascii_immediately() {
        let mut decoder = Utf8Decoder::new();

        assert_eq!(vec!['a'], decoder.accept(b'a'));
    }

    #[test]
    fn decodes_multibyte_sequence_across_calls() {
        let mut decoder = Utf8Decoder::new();

        assert!(decoder.accept(0xe6).is_empty());
        assert!(decoder.accept(0x9e).is_empty());
        assert_eq!(vec!['枝'], decoder.accept(0x9d));
    }

    #[test]
    fn invalid_start_byte_emits_replacement() {
        let mut decoder = Utf8Decoder::new();

        assert_eq!(vec![REPLACEMENT], decoder.accept(0x80));
    }

    #[test]
    fn invalid_continuation_preserves_following_ascii() {
        let mut decoder = Utf8Decoder::new();

        assert!(decoder.accept(0xc2).is_empty());
        assert_eq!(vec![REPLACEMENT, 'a'], decoder.accept(b'a'));
    }

    #[test]
    fn finish_emits_replacement_for_partial_sequence() {
        let mut decoder = Utf8Decoder::new();

        assert!(decoder.accept(0xf0).is_empty());
        assert_eq!(vec![REPLACEMENT], decoder.finish());
        assert!(decoder.finish().is_empty());
    }

    #[test]
    fn overlong_sequence_emits_replacement() {
        let mut decoder = Utf8Decoder::new();

        assert!(decoder.accept(0xe0).is_empty());
        assert!(decoder.accept(0x80).is_empty());
        assert_eq!(vec![REPLACEMENT], decoder.accept(0x80));
    }
}
