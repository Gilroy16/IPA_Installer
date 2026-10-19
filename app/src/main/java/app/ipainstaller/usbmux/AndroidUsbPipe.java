package app.ipainstaller.usbmux;

import static app.ipainstaller.util.I18n.t;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Opens the iPhone's usbmux interface (class 0xFF, subclass 0xFE, protocol 2) using
 * Android's USB host API, switching USB configuration first if needed, like usbmuxd's
 * usb_device_add() does.
 */
public final class AndroidUsbPipe implements UsbPipe {
    public static final int APPLE_VENDOR_ID = 0x05AC;
    private static final int MUX_CLASS = 0xFF, MUX_SUBCLASS = 0xFE, MUX_PROTOCOL = 2;

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint epIn, epOut;
    private volatile boolean closed;

    private AndroidUsbPipe(UsbDeviceConnection conn, UsbInterface iface, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.iface = iface;
        this.epIn = in;
        this.epOut = out;
    }

    public static boolean isApple(UsbDevice d) {
        return d.getVendorId() == APPLE_VENDOR_ID;
    }

    public static AndroidUsbPipe open(UsbManager manager, UsbDevice device, UsbMux.Logger log) throws IOException {
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) throw new IOException(t("usb_open_failed"));
        try {
            int current = currentConfiguration(conn);
            UsbConfiguration target = null;
            UsbConfiguration currentCfg = null;
            for (int i = 0; i < device.getConfigurationCount(); i++) {
                UsbConfiguration c = device.getConfiguration(i);
                if (findMuxInterface(c) == null) continue;
                if (c.getId() == current) currentCfg = c;
                if (target == null || c.getId() > target.getId()) target = c;
            }
            if (target == null) throw new IOException(t("usb_no_mux"));
            UsbConfiguration use = currentCfg != null ? currentCfg : target;
            if (currentCfg == null) {
                log.log(t("usb_switch_config", current, target.getId()));
                if (!conn.setConfiguration(target)) {
                    throw new IOException(t("usb_switch_failed"));
                }
            }
            UsbInterface iface = findMuxInterface(use);
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint e = iface.getEndpoint(i);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e;
                else out = e;
            }
            if (in == null || out == null) throw new IOException(t("usb_no_endpoints"));
            if (!conn.claimInterface(iface, true)) throw new IOException(t("usb_claim_failed"));
            log.log(t("usb_info", use.getId(), iface.getId(), Integer.toHexString(out.getAddress()),
                    Integer.toHexString(in.getAddress()), out.getMaxPacketSize()));
            return new AndroidUsbPipe(conn, iface, in, out);
        } catch (IOException e) {
            conn.close();
            throw e;
        }
    }

    private static UsbInterface findMuxInterface(UsbConfiguration c) {
        for (int i = 0; i < c.getInterfaceCount(); i++) {
            UsbInterface f = c.getInterface(i);
            if (f.getInterfaceClass() == MUX_CLASS && f.getInterfaceSubclass() == MUX_SUBCLASS
                    && f.getInterfaceProtocol() == MUX_PROTOCOL && f.getEndpointCount() == 2 && f.getAlternateSetting() == 0) {
                return f;
            }
        }
        return null;
    }

    /** Standard GET_CONFIGURATION control request. */
    private static int currentConfiguration(UsbDeviceConnection conn) {
        byte[] b = new byte[1];
        int n = conn.controlTransfer(0x80, 0x08, 0, 0, b, 1, 1000);
        return n == 1 ? (b[0] & 0xff) : -1;
    }

    @Override
    public int read(byte[] buf, int len) throws IOException {
        while (!closed) {
            int n = conn.bulkTransfer(epIn, buf, len, 0);
            if (n >= 0) return n;
            if (closed) break;
            throw new IOException(t("usb_read_error"));
        }
        return -1;
    }

    @Override
    public void write(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = conn.bulkTransfer(epOut, buf, off, len - off, 10000);
            if (n < 0) throw new IOException(t("usb_write_error"));
            off += n;
        }
        if (len % epOut.getMaxPacketSize() == 0) {
            conn.bulkTransfer(epOut, buf, 0, 0, 1000);   // zero-length packet terminates the transfer
        }
    }

    @Override
    public int maxPacketSize() {
        return epOut.getMaxPacketSize();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            conn.releaseInterface(iface);
        } catch (Exception ignored) {
            // device may already be gone
        }
        conn.close();
    }
}
