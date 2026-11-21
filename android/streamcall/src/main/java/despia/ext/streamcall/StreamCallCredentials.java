package despia.ext.streamcall;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * What {@code configure} stores, so a Stream push can ring with no page loaded (FCM may start the
 * process in the background). Nothing is built into the app: every value comes from the page, which
 * got the token from the developer's own server. The file lives in the extension's own data
 * directory inside the app sandbox; it is written whole to a temporary file and renamed, so a crash
 * never leaves half a file. A corrupt or partial file reads as "not configured". The token is never
 * logged and never leaves this class except to the Stream client.
 */
public final class StreamCallCredentials {
    static final String FILE = "streamcall.properties";

    public final String apiKey;
    public final String userId;
    public final String userName;
    public final String token;
    public final String tokenUrl;
    public final String fcmProvider;

    public StreamCallCredentials(String apiKey, String userId, String userName, String token, String tokenUrl, String fcmProvider) {
        this.apiKey = apiKey;
        this.userId = userId;
        this.userName = userName;
        this.token = token;
        this.tokenUrl = tokenUrl;
        this.fcmProvider = fcmProvider;
    }

    public StreamCallCredentials withToken(String next) {
        return new StreamCallCredentials(apiKey, userId, userName, next, tokenUrl, fcmProvider);
    }

    public static StreamCallCredentials load(File dir) {
        File f = new File(dir, FILE);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String apiKey = p.getProperty("api_key"), userId = p.getProperty("user_id"), token = p.getProperty("token");
        String provider = p.getProperty("fcm_provider");
        if (!StreamCallRules.isName(apiKey) || !StreamCallRules.isUserId(userId) || token == null || token.isEmpty()
                || !StreamCallRules.isName(provider)) return null;
        return new StreamCallCredentials(apiKey, userId, p.getProperty("user_name"), token, p.getProperty("token_url"), provider);
    }

    public static boolean save(File dir, StreamCallCredentials c) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        Properties p = new Properties();
        p.setProperty("api_key", c.apiKey);
        p.setProperty("user_id", c.userId);
        if (c.userName != null) p.setProperty("user_name", c.userName);
        p.setProperty("token", c.token);
        if (c.tokenUrl != null) p.setProperty("token_url", c.tokenUrl);
        p.setProperty("fcm_provider", c.fcmProvider);
        File tmp = new File(dir, FILE + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
            out.flush();
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(new File(dir, FILE));
    }

    public static void delete(File dir) {
        new File(dir, FILE).delete();
        new File(dir, FILE + ".tmp").delete();
    }
}
