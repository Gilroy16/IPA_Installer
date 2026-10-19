package app.ipainstaller.lockdown;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

import app.ipainstaller.usbmux.UsbMux;
import app.ipainstaller.util.DuplexStream;
import app.ipainstaller.util.Plist;
import app.ipainstaller.util.TlsStream;

/** Client for lockdownd (device port 62078), following libimobiledevice's lockdown.c. */
public final class LockdownClient extends PlistChannel {
    public static final int PORT = 62078;
    public static final String LABEL = "IPAInstaller";

    public static final class LockdownException extends IOException {
        public final String error;

        public LockdownException(String request, String error) {
            super(request + ": " + error);
            this.error = error;
        }
    }

    public static final class ServiceInfo {
        public final int port;
        public final boolean ssl;

        ServiceInfo(int port, boolean ssl) {
            this.port = port;
            this.ssl = ssl;
        }
    }

    private String sessionId;

    public LockdownClient(DuplexStream stream) {
        super(stream);
    }

    public static LockdownClient connect(UsbMux mux) throws IOException {
        return new LockdownClient(mux.connect(PORT));
    }

    public Map<String, Object> request(String name, Object... kv) throws IOException {
        Map<String, Object> msg = Plist.dict("Label", LABEL, "Request", name);
        msg.putAll(Plist.dict(kv));
        send(msg);
        Map<String, Object> reply = receive();
        Object err = reply.get("Error");
        if (err != null) throw new LockdownException(name, String.valueOf(err));
        return reply;
    }

    public String queryType() throws IOException {
        return String.valueOf(request("QueryType").get("Type"));
    }

    public Object getValue(String domain, String key) throws IOException {
        return request("GetValue", "Domain", domain, "Key", key).get("Value");
    }

    /** Sends a Pair request; throws LockdownException e.g. with PairingDialogResponsePending until the user taps Trust. */
    public Map<String, Object> pair(PairRecord record) throws IOException {
        return request("Pair",
                "PairRecord", record.toPairRequest(),
                "ProtocolVersion", "2",
                "PairingOptions", Plist.dict("ExtendedPairingErrors", Boolean.TRUE));
    }

    /** Starts an authenticated session and, if the device asks for it, switches this connection to TLS. */
    public void startSession(PairRecord record) throws IOException {
        Map<String, Object> reply = request("StartSession", "HostID", record.hostId, "SystemBUID", record.systemBuid);
        sessionId = (String) reply.get("SessionID");
        if (Boolean.TRUE.equals(reply.get("EnableSessionSSL"))) {
            stream = wrapTls(stream, record);
        }
    }

    public ServiceInfo startService(String service, byte[] escrowBag) throws IOException {
        Map<String, Object> reply = escrowBag != null
                ? request("StartService", "Service", service, "EscrowBag", escrowBag)
                : request("StartService", "Service", service);
        Object port = reply.get("Port");
        if (!(port instanceof Number)) throw new IOException("StartService " + service + ": no port in reply");
        return new ServiceInfo(((Number) port).intValue(), Boolean.TRUE.equals(reply.get("EnableServiceSSL")));
    }

    public String sessionId() {
        return sessionId;
    }

    /** TLS client using the host certificate from the pair record; the iPhone's certificate is not verified. */
    public static DuplexStream wrapTls(DuplexStream raw, PairRecord record) throws IOException {
        try {
            final X509Certificate cert = PairingCrypto.loadCertificate(record.hostCertPem);
            final PrivateKey key = PairingCrypto.loadPrivateKey(record.hostKeyPem);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[]{new FixedKeyManager(cert, key)}, new TrustManager[]{new TrustAll()}, null);
            SSLEngine engine = ctx.createSSLEngine();
            engine.setUseClientMode(true);
            return new TlsStream(raw, engine);
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    private static final class FixedKeyManager extends X509ExtendedKeyManager {
        private final X509Certificate cert;
        private final PrivateKey key;

        FixedKeyManager(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{"host"};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return "host";
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return "host";
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[]{cert};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
