package nl.pinda.framework.modules.panel;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tweestapsverificatie met een authenticator-app (TOTP, RFC 6238): elke 30 seconden een
 * nieuwe code van 6 cijfers. Werkt met Google Authenticator, Microsoft Authenticator,
 * Authy, Bitwarden, 1Password, enzovoort.
 */
final class TwoFactor {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long PERIOD = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TwoFactor() {
    }

    /** Een nieuw geheim (160 bits) in base32, zoals authenticator-apps het verwachten. */
    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    private static byte[] decode(String secret) {
        String clean = secret.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    /** De huidige tijdstap (elke 30 seconden één hoger). */
    static long currentStep() {
        return System.currentTimeMillis() / 1000 / PERIOD;
    }

    /**
     * Controleert een code. Een halve minuut speling naar voren en achteren mag (klok van de telefoon).
     *
     * @param after codes van deze tijdstap of eerder worden geweigerd (een code werkt maar één keer)
     * @return de tijdstap van de code, of -1 als hij niet klopt
     */
    static long verify(String secret, String code, long after) {
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        if (digits.length() != 6) {
            return -1;
        }
        int expected = Integer.parseInt(digits);
        byte[] key = decode(secret);
        long now = currentStep();
        for (long step = now - 1; step <= now + 1; step++) {
            if (step > after && code(key, step) == expected) {
                return step;
            }
        }
        return -1;
    }

    private static int code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return binary % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is niet beschikbaar", e);
        }
    }

    /** De link die in de QR-code staat (otpauth://...). */
    static String uri(String issuer, String account, String secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=6&period=" + PERIOD;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** De QR-code als plaatje (data-URL met SVG), om in de browser te tonen. */
    static String qrDataUrl(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (matrix.get(x, y)) {
                        path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                    }
                }
            }
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + width + " " + height
                    + "' shape-rendering='crispEdges'><rect width='" + width + "' height='" + height
                    + "' fill='#ffffff'/><path d='" + path + "' fill='#111111'/></svg>";
            return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        } catch (WriterException e) {
            throw new IllegalStateException("Kon de QR-code niet maken", e);
        }
    }

    /** Het geheim in groepjes van 4, makkelijker over te typen. */
    static String pretty(String secret) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(secret.charAt(i));
        }
        return out.toString();
    }
}
