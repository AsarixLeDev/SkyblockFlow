package dev.asarix.skyblockflow;

import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;

/** Checks the named-pipe server's OS account before any OAuth token is transmitted. */
final class DiscordWindowsPipe implements ReadableByteChannel, WritableByteChannel {
  private final WinNT.HANDLE handle;
  private volatile boolean open = true;
  private DiscordWindowsPipe(WinNT.HANDLE handle) { this.handle = handle; }

  static DiscordWindowsPipe open(int index) throws IOException {
    var kernel = Kernel32.INSTANCE;
    // SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION: the pipe cannot impersonate us.
    var handle = kernel.CreateFile("\\\\.\\pipe\\discord-ipc-" + index,
        WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING,
        0x00100000 | 0x00010000, null);
    if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("Discord unavailable");
    boolean accepted = false;
    try {
      var pid = new WinDef.ULONGByReference();
      if (!kernel.GetNamedPipeServerProcessId(handle, pid)) throw new IOException("Cannot verify Discord peer");
      var process = kernel.OpenProcess(0x1000, false, pid.getValue().intValue()); // PROCESS_QUERY_LIMITED_INFORMATION
      if (process == null) throw new IOException("Cannot verify Discord peer");
      try {
        if (!Arrays.equals(userSid(process), userSid(kernel.GetCurrentProcess())))
          throw new IOException("Untrusted local Discord peer");
      } finally { kernel.CloseHandle(process); }
      accepted = true;
      return new DiscordWindowsPipe(handle);
    } catch (RuntimeException e) {
      throw new IOException("Cannot verify Discord peer");
    } finally { if (!accepted) kernel.CloseHandle(handle); }
  }

  private static byte[] userSid(WinNT.HANDLE process) throws IOException {
    var token = new WinNT.HANDLEByReference();
    if (!Advapi32.INSTANCE.OpenProcessToken(process, WinNT.TOKEN_QUERY, token))
      throw new IOException("Cannot verify Discord peer");
    try {
      var size = new IntByReference();
      Advapi32.INSTANCE.GetTokenInformation(token.getValue(), WinNT.TOKEN_INFORMATION_CLASS.TokenUser, null, 0, size);
      if (size.getValue() <= 0 || size.getValue() > 65536) throw new IOException("Cannot verify Discord peer");
      var user = new WinNT.TOKEN_USER(size.getValue());
      if (!Advapi32.INSTANCE.GetTokenInformation(token.getValue(), WinNT.TOKEN_INFORMATION_CLASS.TokenUser, user, size.getValue(), size))
        throw new IOException("Cannot verify Discord peer");
      return user.User.Sid.getBytes();
    } finally { Kernel32.INSTANCE.CloseHandle(token.getValue()); }
  }

  public int read(ByteBuffer target) throws IOException {
    if (!open) throw new ClosedChannelException();
    if (!target.hasRemaining()) return 0;
    byte[] bytes = new byte[Math.min(target.remaining(), 65536)];
    var count = new IntByReference();
    if (!Kernel32.INSTANCE.ReadFile(handle, bytes, bytes.length, count, null)) throw new IOException("Discord pipe closed");
    if (count.getValue() == 0) return -1;
    target.put(bytes, 0, count.getValue());
    return count.getValue();
  }
  public int write(ByteBuffer source) throws IOException {
    if (!open) throw new ClosedChannelException();
    if (!source.hasRemaining()) return 0;
    byte[] bytes = new byte[Math.min(source.remaining(), 65536)];
    source.duplicate().get(bytes);
    var count = new IntByReference();
    if (!Kernel32.INSTANCE.WriteFile(handle, bytes, bytes.length, count, null) || count.getValue() <= 0)
      throw new IOException("Discord pipe closed");
    source.position(source.position() + count.getValue());
    return count.getValue();
  }
  public boolean isOpen() { return open; }
  public synchronized void close() {
    if (open) { open = false; Kernel32.INSTANCE.CloseHandle(handle); }
  }
}
