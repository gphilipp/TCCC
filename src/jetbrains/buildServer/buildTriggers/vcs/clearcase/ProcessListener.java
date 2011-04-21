package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import java.io.OutputStream;

/**
 * @author Gilles Philippart
 */
public interface ProcessListener {
  void onOutTextAvailable(byte[] buff, int offset, int length, OutputStream output);

  void onErrTextAvailable(byte[] buff, int offset, int length, OutputStream output);

  void onOutTextAvailable(String text, OutputStream output);

  void onErrTextAvailable(String text, OutputStream output);
}
