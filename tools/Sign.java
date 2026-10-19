import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs an APK (APK Signature Scheme v2) with a key from a PKCS12 keystore using apksig. */
public class Sign {
    public static void main(String[] a) throws Exception {
        String in = a[0], out = a[1], ks = a[2], pass = a[3], alias = a[4];
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(ks)) {
            store.load(f, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder("CERT", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)   // minSdk 26: v2 is enough
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }
}
