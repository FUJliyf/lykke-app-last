package com.lykke.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内一键更新：
 * 读取 GitHub Releases 最新版本，比较版本号，下载 APK 并调起安装。
 *
 * 【重要】把下面 REPO 改成你的 GitHub 仓库，格式：用户名/仓库名
 */
public class UpdateManager {

    private static final String REPO = "FUJliyf/lykke-app-last";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog progress;
    /** 已下载、等待安装授权的包；授权返回后由 onReturnFromSettings 续装。 */
    private File pendingApk;

    public UpdateManager(Context ctx) {
        this.ctx = ctx;
    }

    /** 启动后台检查；有新版本才弹窗，无更新或失败均静默。 */
    public void check() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "lykke");
                if (conn.getResponseCode() != 200) return;
                JSONObject json = new JSONObject(readAll(conn.getInputStream()));

                String tag = json.optString("tag_name", "").replaceFirst("^[vV]", "").trim();
                if (tag.isEmpty() || compareVer(tag, currentVer()) <= 0) return;

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) return;

                final String ver = tag;
                final String notes = json.optString("body", "").trim();
                final String url = apkUrl;
                main.post(() -> prompt(ver, notes, url));
            } catch (Exception ignored) {
                // 检查更新失败不打扰用户
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void prompt(String ver, String notes, String url) {
        String msg = "发现新版本 v" + ver;
        if (!notes.isEmpty()) msg += "\n\n" + notes;
        new AlertDialog.Builder(ctx)
                .setTitle("有新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> download(url))
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show();
    }

    private void download(String url) {
        showProgress();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "lykke");
                conn.connect();

                File dir = ctx.getExternalCacheDir();
                if (dir == null) dir = ctx.getCacheDir();
                File out = new File(dir, "lykke-update.apk");

                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.flush();
                fos.close();
                is.close();

                main.post(() -> {
                    dismissProgress();
                    beginInstall(out);
                });
            } catch (Exception e) {
                main.post(() -> {
                    dismissProgress();
                    toastFail();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** 下载完成后先校验「安装未知来源」权限；未授权则引导去设置，返回后续装。 */
    private void beginInstall(File apk) {
        if (hasInstallPermission()) {
            install(apk);
        } else {
            pendingApk = apk;
            promptNeedPermission();
        }
    }

    private boolean hasInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        // 8.0 以下走全局「未知来源」开关，由系统安装器兜底
        return true;
    }

    private void promptNeedPermission() {
        new AlertDialog.Builder(ctx)
                .setTitle("需要安装权限")
                .setMessage("更新需要允许安装来自本应用的安装包，请在下一步打开开关后返回。")
                .setPositiveButton("去设置", (d, w) -> openUnknownSourceSettings())
                .setNegativeButton("稍后", (d, w) -> pendingApk = null)
                .show();
    }

    private void openUnknownSourceSettings() {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", ctx.getPackageName(), null));
            } else {
                i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            // 个别 ROM 无该设置页，退回直接安装由系统兜底
            if (pendingApk != null) {
                File apk = pendingApk;
                pendingApk = null;
                install(apk);
            }
        }
    }

    /** 从设置页返回后由 Activity 调用：已授权则继续安装待处理的包。 */
    public void onReturnFromSettings() {
        if (pendingApk == null) return;
        if (hasInstallPermission()) {
            File apk = pendingApk;
            pendingApk = null;
            install(apk);
        }
    }

    private void install(File apk) {
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        } else {
            uri = Uri.fromFile(apk);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private void showProgress() {
        progress = new AlertDialog.Builder(ctx)
                .setTitle("正在下载更新")
                .setMessage("请稍候，完成后会自动安装…")
                .setCancelable(false)
                .show();
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) progress.dismiss();
    }

    private void toastFail() {
        new AlertDialog.Builder(ctx)
                .setTitle("更新失败")
                .setMessage("下载更新失败，请检查网络后重试。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private String currentVer() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 语义化版本比较：a>b 返回 1，相等 0，a<b 返回 -1。 */
    private int compareVer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? safeInt(pa[i]) : 0;
            int y = i < pb.length ? safeInt(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toString("UTF-8");
    }
}
