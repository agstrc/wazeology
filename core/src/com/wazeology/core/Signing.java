package com.wazeology.core;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Signs an apk with the embedded PKCS12 key using apksig (the same library apksigner wraps). The
 * jar is dexed into the installer app and sits on the host classpath (BuildWaze, the gates), so
 * this one code path signs both build targets and is exercised in the build gate. v2+v3 only: Waze itself needs API 32, so nothing older than 28
 * ever verifies these signatures, and the stale v1 remnants are dropped by the graft.
 */
public final class Signing {

    private Signing() {}

    public static void sign(File in, File out, byte[] p12Bytes, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(p12Bytes), password);
        String alias = Pins.P12_ALIAS;
        if (!ks.containsAlias(alias)) {
            alias = ks.aliases().nextElement(); // tolerate a p12 with a different alias
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password);
        Certificate[] chain = ks.getCertificateChain(alias);
        if (key == null || chain == null || chain.length == 0) {
            throw new IllegalStateException("the signing p12 holds no usable key/certificate");
        }
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("wazeology", key, certs)
                .build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(Pins.SIGN_MIN_SDK)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("Wazeology Installer")
                .build();
        signer.sign();
    }
}
