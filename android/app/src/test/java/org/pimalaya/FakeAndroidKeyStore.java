package org.pimalaya;

import android.security.keystore.KeyGenParameterSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A software stand-in for the "AndroidKeyStore" JCA provider, which the
 * JVM Robolectric runs on does not have: AES keys generated under an alias
 * land in a map the keystore reads back, so the production sealing code
 * runs unchanged.
 */
public final class FakeAndroidKeyStore extends Provider {
    static final Map<String, SecretKey> KEYS = new ConcurrentHashMap<>();

    private FakeAndroidKeyStore() {
        super("AndroidKeyStore", 1.0, "test stand-in");
        put("KeyStore.AndroidKeyStore", Store.class.getName());
        put("KeyGenerator.AES", Generator.class.getName());
    }

    /**
     * Registers the provider, replacing one a previous Robolectric sandbox
     * registered: JCA is JVM-wide, but each sandbox loads its own classes.
     */
    static synchronized void install() {
        Security.removeProvider("AndroidKeyStore");
        Security.addProvider(new FakeAndroidKeyStore());
    }

    public static final class Generator extends KeyGeneratorSpi {
        private String alias;

        @Override
        protected void engineInit(SecureRandom random) {}

        @Override
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) {
            alias = ((KeyGenParameterSpec) params).getKeystoreAlias();
        }

        @Override
        protected void engineInit(int keysize, SecureRandom random) {}

        @Override
        protected SecretKey engineGenerateKey() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES");
            KEYS.put(alias, key);
            return key;
        }
    }

    public static final class Store extends KeyStoreSpi {
        @Override
        public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter param) {
            SecretKey key = KEYS.get(alias);
            return key == null ? null : new KeyStore.SecretKeyEntry(key);
        }

        @Override
        public Key engineGetKey(String alias, char[] password) {
            return KEYS.get(alias);
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            return null;
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return null;
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return null;
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
            KEYS.put(alias, (SecretKey) key);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void engineDeleteEntry(String alias) {
            KEYS.remove(alias);
        }

        @Override
        public Enumeration<String> engineAliases() {
            return Collections.enumeration(KEYS.keySet());
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return KEYS.containsKey(alias);
        }

        @Override
        public int engineSize() {
            return KEYS.size();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return KEYS.containsKey(alias);
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return false;
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            return null;
        }

        @Override
        public void engineStore(OutputStream stream, char[] password) {}

        @Override
        public void engineLoad(InputStream stream, char[] password) {}
    }
}
