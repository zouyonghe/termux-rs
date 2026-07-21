use crate::terminal::Size;

pub trait TerminalOutput {
    fn write_to_process(&mut self, bytes: &[u8]);
    fn title_changed(&mut self, old_title: Option<&str>, new_title: &str);
    fn copy_to_clipboard(&mut self, text: &str);
    fn paste_from_clipboard(&mut self);
    fn bell(&mut self);
    fn colors_changed(&mut self);
}

pub trait TerminalSessionClient {
    fn text_changed(&mut self);
    fn title_changed(&mut self);
    fn session_finished(&mut self);
    fn resized(&mut self, size: Size);
    fn cursor_state_changed(&mut self, visible: bool);
}
