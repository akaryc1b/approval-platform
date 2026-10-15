"""JAR-only sampled storage bound; shared Commands remains unchanged."""
import os
from pathlib import Path
import signal
import stat
import threading


class StorageGuard:
    def __init__(self, root, maximum_bytes=256 * 1024 * 1024, maximum_entries=2048, file_limits=None):
        self.root = Path(root)
        self.maximum_bytes = maximum_bytes
        self.maximum_entries = maximum_entries
        self.file_limits = dict(file_limits or {})
        self.peak_bytes = 0
        self.peak_entries = 0
        self.scans = 0
        self.failure = None
        self._stop = threading.Event()
        self._thread = None

    def scan(self):
        if self.root.is_symlink() or not self.root.is_dir():
            raise ValueError('owned output directory required')
        size, count, pending = 0, 0, [self.root]
        while pending:
            directory = pending.pop()
            if directory.is_symlink():
                raise ValueError('output directory symlink rejected before traversal')
            with os.scandir(directory) as entries:
                for entry in entries:
                    count += 1
                    if count > self.maximum_entries:
                        raise ValueError('aggregate output entry limit exceeded')
                    try:
                        info = entry.stat(follow_symlinks=False)
                    except FileNotFoundError:
                        # Maven may remove an owned temporary file between enumeration and stat.
                        continue
                    if stat.S_ISLNK(info.st_mode):
                        raise ValueError('output symlink rejected')
                    if stat.S_ISDIR(info.st_mode):
                        pending.append(Path(entry.path))
                    elif stat.S_ISREG(info.st_mode):
                        limit = self.file_limits.get(str(Path(entry.path).relative_to(self.root)))
                        if limit is not None and info.st_size > limit:
                            raise ValueError('individual output file byte limit exceeded')
                        size += info.st_size
                        if size > self.maximum_bytes:
                            raise ValueError('aggregate output byte limit exceeded')
                    else:
                        raise ValueError('non-regular output entry rejected')
        self.scans += 1
        self.peak_bytes = max(self.peak_bytes, size)
        self.peak_entries = max(self.peak_entries, count)
        return size, count

    def _watch(self):
        while not self._stop.wait(0.1):
            try:
                self.scan()
            except BaseException as failure:
                self.failure = str(failure)
                # Main-thread handler interrupts Commands; its existing finally
                # kills and reaps the whole owned child process group.
                os.kill(os.getpid(), signal.SIGTERM)
                return

    def start(self):
        if self._thread is not None or self.failure is not None:
            raise ValueError('fresh storage watcher state required')
        self.scan(); self._stop.clear()
        self._thread = threading.Thread(target=self._watch, name='jar-storage-bound', daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1)
            if self._thread.is_alive():
                raise ValueError('storage watcher did not stop')
            self._thread = None

    def report(self):
        return {'maximumBytes': self.maximum_bytes, 'maximumEntries': self.maximum_entries,
            'fileLimits': self.file_limits,
            'pollIntervalSeconds': 0.1, 'sampledBound': True, 'scans': self.scans,
            'peakObservedBytes': self.peak_bytes, 'peakObservedEntries': self.peak_entries,
            'failure': self.failure}
