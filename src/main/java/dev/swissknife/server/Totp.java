package dev.swissknife.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP (RFC 6238) real, sem dependências externas: geração de segredo, cálculo de código
 * de 6 dígitos e verificação com janela de tolerância — para MFA na administração do portal/backends.
 */
public final class Totp {
    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;

    private Totp() {}

    /** Gera um novo segredo aleatório codificado em Base32 (compatível com apps como Google/Microsoft Authenticator). */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** URI otpauth:// para gerar o QR code de provisionamento em qualquer app TOTP padrão. */
    public static String provisioningUri(String secret, String accountName, String issuer) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(accountName) +
            "?secret=" + secret + "&issuer=" + urlEncode(issuer) + "&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** Código válido para o instante atual. */
    public static String currentCode(String base32Secret) {
        return code(base32Secret, System.currentTimeMillis() / 1000 / STEP_SECONDS);
    }

    /** Verifica um código informado pelo usuário, tolerando +/-1 passo de 30s (skew de relógio). */
    public static boolean verify(String base32Secret, String submittedCode) {
        long step = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        for (long delta = -1; delta <= 1; delta++)
            if (code(base32Secret, step + delta).equals(submittedCode)) return true;
        return false;
    }

    private static String code(String base32Secret, long counter) {
        byte[] key = base32Decode(base32Secret);
        byte[] counterBytes = new byte[8];
        for (int i = 7; i >= 0; i--) { counterBytes[i] = (byte) (counter & 0xFF); counter >>= 8; }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counterBytes);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) |
                ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String base32Encode(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF); bitsLeft += 8;
            while (bitsLeft >= 5) { out.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 0x1F)); bitsLeft -= 5; }
        }
        if (bitsLeft > 0) out.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        return out.toString();
    }
    private static byte[] base32Decode(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = value.toUpperCase(java.util.Locale.ROOT).replace("=", "");
        int buffer = 0, bitsLeft = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int index = alphabet.indexOf(c);
            if (index < 0) continue;
            buffer = (buffer << 5) | index; bitsLeft += 5;
            if (bitsLeft >= 8) { out.write((buffer >> (bitsLeft - 8)) & 0xFF); bitsLeft -= 8; }
        }
        return out.toByteArray();
    }
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
