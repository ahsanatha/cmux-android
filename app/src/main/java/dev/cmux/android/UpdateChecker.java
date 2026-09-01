package dev.cmux.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks the public GitHub releases for a newer APK and offers to download and
 * install it. The app signs its own updates with the same release key, so no
 * Play Store is required.
 */
public final class UpdateChecker {

    private static final String RELEASES_LATEST =
        "https://api.github.com/repos/ahsanatha/cmux-android/releases/latest";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public UpdateChecker(Activity activity) {
        this.activity = activity;
    }

    /** Runs a background check; on success shows an update dialog, otherwise stays silent. */
    public void checkSilently() {
        executor.execute(() -> {
            Release release = fetchLatest();
            if (release == null || !release.isNewerThan(currentVersionCode(), currentVersionName())) {
                return;
            }
            activity.runOnUiThread(() -> promptInstall(release));
        });
    }

    /** User-triggered check; always reports a result. */
    public void checkManually() {
        executor.execute(() -> {
            try {
                Release release = fetchLatest();
                if (release == null) {
                    fail("Could not reach GitHub releases.");
                    return;
                }
                if (!release.isNewerThan(currentVersionCode(), currentVersionName())) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                        "You are on the latest version (" + currentVersionName() + ")",
                        Toast.LENGTH_SHORT).show());
                    return;
                }
                activity.runOnUiThread(() -> promptInstall(release));
            } catch (Exception error) {
                fail(errorMessage(error));
            }
        });
    }

    private void promptInstall(Release release) {
        new AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Version " + release.version + " is available. Download and install "
                + release.apkName + "?")
            .setPositiveButton("Install", (dialog, which) -> download(release))
            .setNegativeButton("Later", null)
            .show();
    }

    private void download(Release release) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                File destination = new File(
                    activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    release.apkName);
                HttpURLConnection connection = open(release.apkUrl);
                connection.connect();
                if (connection.getResponseCode() / 100 != 2) {
                    fail("Download failed: HTTP " + connection.getResponseCode());
                    return;
                }
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[32_768];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        output.write(buffer, 0, read);
                    }
                }
                activity.runOnUiThread(() -> install(destination));
            } catch (Exception error) {
                fail(errorMessage(error));
            }
        });
    }

    private void install(File apk) {
        PackageManager packageManager = activity.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity,
                "Allow \"Install unknown apps\" for cmux, then try the update again.",
                Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
            activity.getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    private Release fetchLatest() {
        try {
            HttpURLConnection connection = open(RELEASES_LATEST);
            connection.connect();
            if (connection.getResponseCode() / 100 != 2) return null;
            StringBuilder body = new StringBuilder();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16_384];
                for (int read; (read = input.read(buffer)) != -1;) {
                    body.append(new String(buffer, 0, read, "UTF-8"));
                }
            }
            JSONObject release = new JSONObject(body.toString());
            String apkUrl = null;
            String apkName = null;
            org.json.JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.getJSONObject(index);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        apkName = name;
                        break;
                    }
                }
            }
            if (apkUrl == null) return null;
            return new Release(release.optString("tag_name", ""), apkUrl, apkName);
        } catch (Exception error) {
            return null;
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "cmux-android-updater");
        return connection;
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException error) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            PackageInfo info = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private void fail(String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }

    private static String errorMessage(Exception error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    private static final class Release {
        final String version;
        final String apkUrl;
        final String apkName;

        Release(String version, String apkUrl, String apkName) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
        }

        boolean isNewerThan(long installedCode, String installedName) {
            int[] tag = versionTupleOf(version);
            int[] current = versionTupleOf(installedName);
            for (int index = 0; index < 3; index++) {
                if (tag[index] != current[index]) return tag[index] > current[index];
            }
            return false;
        }

        private static int[] versionTupleOf(String text) {
            int[] tuple = new int[] {0, 0, 0};
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("v?(\\d+)\\.(\\d+)\\.(\\d+)").matcher(text == null ? "" : text);
            if (matcher.find()) {
                tuple[0] = Integer.parseInt(matcher.group(1));
                tuple[1] = Integer.parseInt(matcher.group(2));
                tuple[2] = Integer.parseInt(matcher.group(3));
            }
            return tuple;
        }
    }
}
