use unicode_width::UnicodeWidthChar;

pub fn width(value: char) -> usize {
    UnicodeWidthChar::width_cjk(value).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::width;

    #[test]
    fn matches_selected_termux_wcwidth_cases() {
        assert_eq!(1, width('a'));
        assert_eq!(0, width('\u{0302}'));
        assert_eq!(2, width('中'));
        assert_eq!(2, width('\u{1f428}'));
        assert_eq!(1, width('\u{1f781}'));
    }
}
