package app.ipainstaller.services;

import java.io.IOException;
import java.util.Map;

import app.ipainstaller.lockdown.PlistChannel;
import app.ipainstaller.util.DuplexStream;
import app.ipainstaller.util.Plist;

/** com.apple.mobile.installation_proxy client (see libimobiledevice installation_proxy.c). */
public final class InstallationProxy extends PlistChannel {
    public static final String SERVICE = "com.apple.mobile.installation_proxy";

    public interface Progress {
        void onStatus(String status, int percent);
    }

    public static final class InstallException extends IOException {
        public final String error;

        InstallException(String error, String description) {
            super(error + (description != null ? ": " + description : ""));
            this.error = error;
        }
    }

    public InstallationProxy(DuplexStream s) {
        super(s);
    }

    /**
     * Installs a package already uploaded via AFC, e.g. "PublicStaging/app.ipa".
     * Blocks until the device reports Complete or an error.
     */
    public void install(String packagePath, Progress progress) throws IOException {
        send(Plist.dict(
                "Command", "Install",
                "PackagePath", packagePath,
                "ClientOptions", Plist.dict()));
        while (true) {
            Map<String, Object> m = receive();
            Object error = m.get("Error");
            if (error != null) {
                Object desc = m.get("ErrorDescription");
                throw new InstallException(String.valueOf(error), desc != null ? String.valueOf(desc) : null);
            }
            String status = m.get("Status") != null ? String.valueOf(m.get("Status")) : null;
            int pct = m.get("PercentComplete") instanceof Number ? ((Number) m.get("PercentComplete")).intValue() : -1;
            if ("Complete".equals(status)) {
                if (progress != null) progress.onStatus(status, 100);
                return;
            }
            if (progress != null && status != null) progress.onStatus(status, pct);
        }
    }
}
