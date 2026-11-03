package movierec.service;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds an SSLContext that trusts Java's normal certificate list AND the Windows certificate store.
 *
 * Why: antivirus programs (Avast, Kaspersky...) and company proxies scan HTTPS by presenting their own
 * certificate. Windows and browsers trust it, but Java does not, so Java fails with
 * "PKIX path building failed". Trusting the Windows store fixes that. Certificate and hostname
 * checking stay fully ON - this only adds the roots your own computer already trusts.
 */
public final class SystemTrust {
    private SystemTrust() {}

    public static SSLContext context() {
        try {
            List<X509TrustManager> managers = new ArrayList<>();
            managers.add(managerFor(null));                                  // Java's default list
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                for (String type : new String[] {"Windows-ROOT", "Windows-ROOT-LOCALMACHINE"}) {
                    try {
                        KeyStore ks = KeyStore.getInstance(type);
                        ks.load(null, null);
                        managers.add(managerFor(ks));
                    } catch (Exception ignored) { /* store not available - skip it */ }
                }
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new Combined(managers)}, null);
            return ctx;
        } catch (Exception e) {
            try { return SSLContext.getDefault(); } catch (Exception e2) { throw new IllegalStateException(e2); }
        }
    }

    private static X509TrustManager managerFor(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager tm : f.getTrustManagers()) if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException("no X509TrustManager");
    }

    /** A server certificate is accepted if ANY of the trust sources accepts it. */
    private static final class Combined implements X509TrustManager {
        private final List<X509TrustManager> managers;
        Combined(List<X509TrustManager> managers) { this.managers = managers; }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager m : managers) {
                try { m.checkServerTrusted(chain, authType); return; }
                catch (CertificateException e) { if (first == null) first = e; }
            }
            throw first != null ? first : new CertificateException("no trust manager available");
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager m : managers) for (X509Certificate c : m.getAcceptedIssuers()) all.add(c);
            return all.toArray(new X509Certificate[0]);
        }
    }
}
