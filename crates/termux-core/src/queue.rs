use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QueueRead {
    Data(usize),
    Empty,
    Closed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QueueClosed;

pub trait ProcessInput {
    fn write(&self, bytes: &[u8]) -> Result<(), QueueClosed>;
}

pub trait ProcessOutput {
    fn read(&self, output: &mut [u8], blocking: bool) -> QueueRead;
}

pub struct ByteQueue {
    capacity: usize,
    state: Mutex<QueueState>,
    readable: Condvar,
    writable: Condvar,
}

struct QueueState {
    bytes: VecDeque<u8>,
    open: bool,
}

impl ByteQueue {
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "queue capacity must be greater than zero");
        Self {
            capacity,
            state: Mutex::new(QueueState {
                bytes: VecDeque::with_capacity(capacity),
                open: true,
            }),
            readable: Condvar::new(),
            writable: Condvar::new(),
        }
    }

    /// Closes the queue and wakes blocked readers and writers. Buffered bytes
    /// remain readable before subsequent reads return `QueueRead::Closed`.
    pub fn close(&self) {
        let mut state = self.state.lock().expect("queue mutex poisoned");
        state.open = false;
        self.readable.notify_all();
        self.writable.notify_all();
    }

    pub fn write(&self, bytes: &[u8]) -> Result<(), QueueClosed> {
        let mut state = self.state.lock().expect("queue mutex poisoned");
        for byte in bytes {
            while state.bytes.len() == self.capacity && state.open {
                state = self.writable.wait(state).expect("queue mutex poisoned");
            }
            if !state.open {
                return Err(QueueClosed);
            }
            state.bytes.push_back(*byte);
            self.readable.notify_one();
        }
        Ok(())
    }

    pub fn read(&self, output: &mut [u8], blocking: bool) -> QueueRead {
        if output.is_empty() {
            return QueueRead::Data(0);
        }

        let mut state = self.state.lock().expect("queue mutex poisoned");
        while state.bytes.is_empty() && state.open {
            if !blocking {
                return QueueRead::Empty;
            }
            state = self.readable.wait(state).expect("queue mutex poisoned");
        }
        if state.bytes.is_empty() && !state.open {
            return QueueRead::Closed;
        }

        let was_full = state.bytes.len() == self.capacity;
        let mut count = 0;
        while count < output.len() {
            let Some(byte) = state.bytes.pop_front() else {
                break;
            };
            output[count] = byte;
            count += 1;
        }
        if was_full {
            self.writable.notify_one();
        }
        QueueRead::Data(count)
    }
}

impl ProcessInput for ByteQueue {
    fn write(&self, bytes: &[u8]) -> Result<(), QueueClosed> {
        Self::write(self, bytes)
    }
}

impl ProcessOutput for ByteQueue {
    fn read(&self, output: &mut [u8], blocking: bool) -> QueueRead {
        Self::read(self, output, blocking)
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, mpsc};
    use std::thread;

    use super::{ByteQueue, QueueRead};

    #[test]
    fn nonblocking_read_reports_empty_queue() {
        let queue = ByteQueue::new(2);
        let mut output = [0; 1];

        assert_eq!(QueueRead::Empty, queue.read(&mut output, false));
    }

    #[test]
    fn blocking_read_receives_written_bytes() {
        let queue = Arc::new(ByteQueue::new(2));
        let reader = Arc::clone(&queue);
        let (sender, receiver) = mpsc::channel();

        thread::spawn(move || {
            let mut output = [0; 2];
            sender
                .send((reader.read(&mut output, true), output))
                .unwrap();
        });
        queue.write(b"ab").unwrap();

        assert_eq!((QueueRead::Data(2), *b"ab"), receiver.recv().unwrap());
    }

    #[test]
    fn close_wakes_reads_and_rejects_writes() {
        let queue = Arc::new(ByteQueue::new(1));
        let reader = Arc::clone(&queue);
        let (sender, receiver) = mpsc::channel();

        thread::spawn(move || {
            let mut output = [0; 1];
            sender.send(reader.read(&mut output, true)).unwrap();
        });
        queue.close();

        assert_eq!(QueueRead::Closed, receiver.recv().unwrap());
        assert!(queue.write(b"a").is_err());
    }

    #[test]
    fn read_wakes_writer_waiting_for_capacity() {
        let queue = Arc::new(ByteQueue::new(1));
        queue.write(b"a").unwrap();
        let writer = Arc::clone(&queue);
        let (started, ready) = mpsc::channel();
        let (completed, done) = mpsc::channel();

        thread::spawn(move || {
            started.send(()).unwrap();
            completed.send(writer.write(b"b")).unwrap();
        });
        ready.recv().unwrap();
        let mut output = [0; 1];
        assert_eq!(QueueRead::Data(1), queue.read(&mut output, true));
        assert_eq!([b'a'], output);
        assert!(done.recv().unwrap().is_ok());
        assert_eq!(QueueRead::Data(1), queue.read(&mut output, true));
        assert_eq!([b'b'], output);
    }
}
