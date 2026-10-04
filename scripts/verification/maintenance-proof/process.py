"""Bounded local child helper; importing it performs no subprocess or device I/O."""
import os
import subprocess
import threading
import time

def execute(args, cwd, env, timeout, limit, log=None, build_tree=False):
    chunks, errors, overflow = [], [], threading.Event()
    child = subprocess.Popen([str(v) for v in args], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
    def collect():
        total = 0
        try:
            while block := child.stdout.read(65536):
                chunks.append(block[:max(0, limit - total)])
                total += len(block)
                if total > limit:
                    overflow.set(); break
        except BaseException as error:
            errors.append(type(error).__name__)
    reader = threading.Thread(target=collect, daemon=True)
    reader.start()
    start = time.monotonic()
    failure = None
    try:
        while child.poll() is None:
            if overflow.is_set() or errors or time.monotonic() - start >= timeout:
                raise TimeoutError('Owned command exceeded deadline/output bound')
            time.sleep(.05)
    except BaseException as error:
        failure = type(error).__name__ + ': ' + str(error)
    finally:
        if child.poll() is None:
            if build_tree:
                # Exact just-created local Gradle process tree, never an ADB daemon/device.
                subprocess.run(['C:/Windows/System32/taskkill.exe', '/PID', str(child.pid), '/T', '/F'],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=30, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                child.kill()
        child.wait(timeout=10); reader.join(timeout=5)
        raw = b''.join(chunks)
        if log is not None:
            with log.open('xb') as stream:
                stream.write(raw)
        if reader.is_alive() or errors or overflow.is_set():
            failure = failure or 'Incomplete bounded command output'
        else:
            child.stdout.close()
    return raw, {'exitCode': child.returncode, 'elapsedSeconds': time.monotonic() - start,
                 'outputBytes': len(raw), 'failure': failure, 'overflow': overflow.is_set(), 'readerErrors': errors}

def environment(java=None):
    allowed = {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP'}
    if java is not None:
        allowed |= {'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env['PATH'] = 'C:/Windows/System32'
    if java is not None:
        env['JAVA_HOME'] = str(java)
        env['PATH'] = str(java / 'bin') + os.pathsep + env['PATH']
    return env
