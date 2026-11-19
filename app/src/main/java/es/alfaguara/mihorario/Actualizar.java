package es.alfaguara.mihorario;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 6.13: actualizar la app desde la propia app.
 * - Al abrirla compara la versión instalada con la versión de la app que anuncia la web (widget.php?k=…&f=ver;
 *   si la web aún no lo tiene, con la última Release de GitHub) y, si la instalada es más antigua, avisa.
 * - «⋯ Más → Actualizar app»: descarga el APK de la última Release con DownloadManager y abre el instalador
 *   (la primera vez Android pide permitir que «Mi horario» instale apps). Si algo falla, lo abre en el navegador.
 */
final class Actualizar {
    static final String APK = "https://github.com/juaniruela/mihorario-android/releases/latest/download/MiHorario.apk";
    private static final String API = "https://api.github.com/repos/juaniruela/mihorario-android/releases/latest";
    private static final String MIME = "application/vnd.android.package-archive";
    private static long bajando = -1;

    private Actualizar() {}

    /** «6.12 · 19/11/2026» o «v6.12.16» → «6.12». */
    static String limpia(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int i = v.indexOf(' '); if (i > 0) v = v.substring(0, i);
        String[] p = v.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : v;
    }

    /** Compara «6.9» y «6.12» como números (6.9 < 6.12). */
    static int cmp(String a, String b) {
        String[] x = limpia(a).split("\\."), y = limpia(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? num(x[i]) : 0, w = i < y.length ? num(y[i]) : 0;
            if (u != w) return u < w ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "MiHorario-Android");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        if (in != null) { while ((n = in.read(b)) > 0) o.write(b, 0, n); in.close(); }
        c.disconnect();
        if (code >= 400) throw new IllegalStateException("HTTP " + code);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Versión de la app que debería tener: la que anuncia la web; si no, la de la última Release. "" si no se sabe. */
    private static String disponible(Context c) {
        String e = Datos.enlace(c);
        if (e != null) try {
            String t = get(e + "&f=ver").trim();
            if (t.startsWith("{")) { String v = new JSONObject(t).optString("version_app", ""); if (!v.isEmpty()) return limpia(v); }
        } catch (Exception ignored) {}
        try { return limpia(new JSONObject(get(API)).optString("tag_name", "")); } catch (Exception ignored) {}
        return "";
    }

    /** Al abrir la app (manual = false) o desde «Actualizar app» (manual = true). */
    static void comprobar(Activity a, boolean manual) {
        final String inst = limpia(HorarioWidget.version(a));
        if (manual) Toast.makeText(a, "Buscando la última versión…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String disp = disponible(a);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (a.isFinishing()) return;
                if (!disp.isEmpty() && cmp(inst, disp) < 0) {
                    new AlertDialog.Builder(a).setTitle("Hay que actualizar la app")
                            .setMessage("Tienes Mi horario " + inst + " y la versión actual es la " + disp + ".\n\nSe descargará e instalará encima (no se pierde nada).")
                            .setPositiveButton("Actualizar ahora", (d, w) -> descargar(a))
                            .setNegativeButton("Más tarde", null).show();
                } else if (manual) {
                    new AlertDialog.Builder(a).setTitle("Actualizar la app")
                            .setMessage(disp.isEmpty() ? "No se ha podido comprobar la última versión. ¿Descargar e instalar la última de todos modos?"
                                    : "Ya tienes la última versión (" + inst + "). ¿Volver a instalarla de todos modos?")
                            .setPositiveButton("Descargar e instalar", (d, w) -> descargar(a))
                            .setNegativeButton("Cancelar", null).show();
                }
            });
        }).start();
    }

    /** Descarga el APK con DownloadManager y abre el instalador al terminar. */
    static void descargar(Activity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("Permitir instalar la actualización")
                    .setMessage("Solo la primera vez: en la pantalla que se abre, activa «Permitir de esta fuente» para Mi horario, vuelve atrás y pulsa otra vez «Actualizar app».")
                    .setPositiveButton("Abrir ajustes", (d, w) -> {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception e) { navegador(a); }
                    })
                    .setNeutralButton("Usar el navegador", (d, w) -> navegador(a))
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            File dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) { File f = new File(dir, "MiHorario.apk"); if (f.exists()) f.delete(); }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(APK))
                    .setTitle("Mi horario").setDescription("Descargando la nueva versión")
                    .setMimeType(MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "MiHorario.apk");
            Context app = a.getApplicationContext();
            BroadcastReceiver rec = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != bajando) return;
                    try { app.unregisterReceiver(this); } catch (Exception ignored) {}
                    bajando = -1;
                    instalar(app, dm, id);
                }
            };
            IntentFilter fl = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rec, fl, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(rec, fl);
            bajando = dm.enqueue(r);
            Toast.makeText(a, "Descargando la nueva versión… al terminar se abrirá el instalador.", Toast.LENGTH_LONG).show();
        } catch (Exception e) { navegador(a); }
    }

    private static void instalar(Context c, DownloadManager dm, long id) {
        try {
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            try (android.database.Cursor cu = dm.query(q)) {
                if (cu == null || !cu.moveToFirst() || cu.getInt(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                    Toast.makeText(c, "No se ha podido descargar la actualización. Inténtalo de nuevo.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Uri u = dm.getUriForDownloadedFile(id);
            if (u == null) throw new IllegalStateException("sin archivo");
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        }
    }

    /** Plan B: el navegador descarga el APK y se instala tocando la descarga. */
    static void navegador(Activity a) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APK))); }
        catch (Exception e) { Toast.makeText(a, "No hay ningún navegador para descargarla", Toast.LENGTH_LONG).show(); }
    }
}
