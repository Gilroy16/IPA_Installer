package app.ipainstaller.usbmux;

import java.io.IOException;

/** A pair of bulk endpoints to the iPhone's usbmux interface. */
public interface UsbPipe {
    /** Blocks until a bulk IN transfer completes; returns the number of bytes received. */
    int read(byte[] buf, int len) throws IOException;

    /** Sends one bulk OUT transfer containing exactly one mux packet. */
    void write(byte[] buf, int len) throws IOException;

    int maxPacketSize();

    void close();
}
