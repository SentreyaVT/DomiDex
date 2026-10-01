import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Read-only, dependency-free AAB JAR-signature verification (JDK source launcher). */
public final class VerifyBundle {
    // Other META-INF content is payload and MUST be signed, too.
    private static final Pattern SIGNING_CONTROL = Pattern.compile(
        "META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC))", Pattern.CASE_INSENSITIVE);

    private static String fingerprint(Certificate certificate) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static String expectedFingerprint(String value) {
        String normalized = value.replace(":", "").replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("Expected certificate fingerprint must be SHA-256 (64 hexadecimal digits).");
        }
        return normalized;
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2) {
                throw new IllegalArgumentException("Usage: java VerifyBundle.java bundle.aab [expected-certificate-sha256]");
            }
            Path path = Path.of(args[0]);
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Bundle is not a regular file.");
            String expected = args.length == 2 ? expectedFingerprint(args[1]) : null;
            Set<String> commonSigners = null;
            Set<String> seen = new HashSet<>();
            int payloadCount = 0;
            byte[] buffer = new byte[65536];
            try (JarFile jar = new JarFile(path.toFile(), true)) {
                if (jar.getManifest() == null) throw new SecurityException("Bundle has no signing manifest.");
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!seen.add(entry.getName())) throw new SecurityException("Duplicate ZIP entry: " + entry.getName());
                    if (entry.isDirectory()) continue;
                    // JarFile only checks the cryptographic digest after the complete stream is read.
                    try (InputStream stream = jar.getInputStream(entry)) {
                        while (stream.read(buffer) != -1) { /* Verify all bytes, not merely the manifest. */ }
                    }
                    if (SIGNING_CONTROL.matcher(entry.getName()).matches()) continue;
                    CodeSigner[] signers = entry.getCodeSigners();
                    if (signers == null || signers.length == 0) {
                        throw new SecurityException("Unsigned bundle payload: " + entry.getName());
                    }
                    Set<String> entrySigners = new TreeSet<>();
                    for (CodeSigner signer : signers) {
                        var certificates = signer.getSignerCertPath().getCertificates();
                        if (certificates.isEmpty()) throw new SecurityException("Signer certificate is missing.");
                        entrySigners.add(fingerprint(certificates.get(0)));
                    }
                    if (commonSigners == null) commonSigners = entrySigners;
                    else if (!commonSigners.equals(entrySigners)) {
                        throw new SecurityException("Inconsistent signer sets in bundle payload: " + entry.getName());
                    }
                    payloadCount++;
                }
            }
            if (payloadCount == 0 || commonSigners == null) throw new SecurityException("No signed payload entries.");
            if (expected != null && !commonSigners.equals(Set.of(expected))) {
                throw new SecurityException("Bundle signing certificate does not match the expected release certificate.");
            }
            System.out.println("{\"certificate_sha256\":[\"" + String.join("\",\"", commonSigners)
                + "\"],\"signed_payload_entries\":" + payloadCount + "}");
        } catch (Exception error) {
            System.err.println("Bundle verification failed: " + error.getMessage());
            System.exit(1);
        }
    }
}
