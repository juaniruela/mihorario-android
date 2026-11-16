package es.alfaguara.mihorario;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * App «Mi horario». Tres pantallas con la barra de abajo:
 *  - Día: la ventana del widget (solo consulta, con el enlace privado).
 *  - Tareas: la app web completa en modo móvil (Tareas, Notas, Proyectos, Actas…), con tu usuario y contraseña.
 *  - Servicios: Reservas e Incidencias de la app web.
 * La sesión de Tareas/Servicios se queda guardada en el móvil hasta que pulses «Salir» en Mi cuenta.
 */
public class MainActivity extends Activity {
    static final String EXTRA_DIA = "dia";
    private static final int ELEGIR_ARCHIVO = 41, PERMISO_GUARDAR = 42;

    private WebView web, webTar, webSer;
    /** Pantalla para pegar el enlace. */
    private View config, barra;
    private EditText campo;
    private ProgressBar carga;
    private TextView tabDia, tabTar, tabSer;
    private int actual = 0;            // 0 Día · 1 Tareas · 2 Servicios
    private final boolean[] cargada = new boolean[3];
    private ValueCallback<Uri[]> subida;
    private String[] pendiente;        // descarga a la espera del permiso (Android 8–9)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        webTar = findViewById(R.id.webTar);
        webSer = findViewById(R.id.webSer);
        config = findViewById(R.id.config);
        barra = findViewById(R.id.barra);
        campo = findViewById(R.id.campo);
        carga = findViewById(R.id.carga);
        tabDia = findViewById(R.id.tabDia);
        tabTar = findViewById(R.id.tabTar);
        tabSer = findViewById(R.id.tabSer);
        TextView ayuda = findViewById(R.id.ayuda);
        ayuda.setText(R.string.ayuda);

        Button pegar = findViewById(R.id.btPegar);
        pegar.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                campo.setText(t);
            } else Toast.makeText(this, "No hay nada copiado", Toast.LENGTH_SHORT).show();
        });
        Button guardar = findViewById(R.id.btGuardar);
        guardar.setOnClickListener(x -> guardar(campo.getText().toString()));

        preparar(web, false);
        preparar(webTar, true);
        preparar(webSer, true);
        tabDia.setOnClickListener(x -> ir(0));
        tabTar.setOnClickListener(x -> ir(1));
        tabSer.setOnClickListener(x -> ir(2));
        findViewById(R.id.tabMas).setOnClickListener(this::menuMas);
        // 1.6: permiso de notificaciones (Android 13+) para el aviso de fichar en Séneca
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 43);

        if (!recibir(getIntent())) mostrar();
    }

    /** Ajustes comunes de las tres WebView. app=true: Tareas/Servicios (la app web completa). */
    private void preparar(WebView w, boolean app) {
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(app ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        ws.setMediaPlaybackRequiresUserGesture(true);
        if (app) w.addJavascriptInterface(new Puente(), "AppMovil");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String e = Datos.enlace(MainActivity.this);
                Uri base = e != null ? Uri.parse(e) : null;
                boolean mismo = base != null && u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost());
                String path = u.getPath() == null ? "" : u.getPath();
                if (!app && path.endsWith("widget.php")) return false;          // la ventana se queda dentro
                if (app && mismo && !path.endsWith("widget.php")) return false;   // la app web, también
                if (!app && mismo && (path.endsWith("/") || path.endsWith("index.html"))) { ir(1); return true; }  // «Abrir la app» → Tareas
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) { if (v == vista(actual)) carga.setVisibility(View.VISIBLE); }
            @Override
            public void onPageFinished(WebView v, String url) { carga.setVisibility(View.GONE); }
        });
        // con un WebChromeClient funcionan los avisos y confirmaciones (alert/confirm) y subir archivos
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (subida != null) subida.onReceiveValue(null);
                subida = cb;
                try {
                    Intent i = p.createIntent();
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i, "Elegir archivo"), ELEGIR_ARCHIVO);
                } catch (Exception e) {
                    subida = null;
                    Toast.makeText(MainActivity.this, "No hay ninguna app para elegir archivos", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
        w.setDownloadListener((url, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == ELEGIR_ARCHIVO) {
            if (subida != null) {
                Uri[] r = null;
                if (res == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        r = new Uri[n];
                        for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) r = new Uri[]{data.getData()};
                }
                subida.onReceiveValue(r);
                subida = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private WebView vista(int i) { return i == 1 ? webTar : i == 2 ? webSer : web; }

    private String baseApp() {
        String e = Datos.enlace(this);
        if (e == null) return null;
        int i = e.indexOf("widget.php");
        return i < 0 ? null : e.substring(0, i) + "index.html";
    }

    /** Cambia de pantalla (y la carga la primera vez). */
    private void ir(int i) {
        if (Datos.enlace(this) == null) return;
        actual = i;
        web.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        webTar.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        webSer.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        config.setVisibility(View.GONE);
        barra.setVisibility(View.VISIBLE);
        TextView[] T = {tabDia, tabTar, tabSer};
        for (int k = 0; k < 3; k++) {
            T[k].setAlpha(k == i ? 1f : .6f);
            T[k].setTypeface(null, k == i ? Typeface.BOLD : Typeface.NORMAL);
            T[k].setBackgroundColor(k == i ? 0x33FFFFFF : 0);
        }
        if (i > 0 && !cargada[i]) {
            String b = baseApp();
            if (b != null) { vista(i).loadUrl(b + (i == 1 ? "?movil=tareas" : "?movil=servicios")); cargada[i] = true; }
        }
        invalidateOptionsMenu();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (!recibir(i)) mostrar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Datos.enlace(this) != null) HorarioWidget.refrescar(this);
    }

    /** Enlace compartido con la app desde Chrome, el correo, WhatsApp… */
    private boolean recibir(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null) return false;
        guardar(t);
        return true;
    }

    private void guardar(String texto) {
        String e = Datos.limpiar(texto);
        if (e == null) {
            new AlertDialog.Builder(this).setTitle("Enlace no válido")
                    .setMessage("Pega el enlace del widget que da la app de Horarios en Mi cuenta → «Mi horario en el móvil». Tiene que contener «widget.php?k=».")
                    .setPositiveButton("Vale", null).show();
            return;
        }
        Datos.guardarEnlace(this, e);
        cargada[1] = cargada[2] = false;
        HorarioWidget.refrescar(this);
        Toast.makeText(this, "Enlace guardado. Ya puedes añadir el widget a la pantalla de inicio.", Toast.LENGTH_LONG).show();
        mostrar();
    }

    /** Desde el icono o el widget: abre la pantalla Día (en el día elegido en el widget). */
    private void mostrar() {
        String e = Datos.enlace(this);
        if (e == null) {
            config.setVisibility(View.VISIBLE);
            barra.setVisibility(View.GONE);
            web.setVisibility(View.GONE);
            webTar.setVisibility(View.GONE);
            webSer.setVisibility(View.GONE);
            carga.setVisibility(View.GONE);
            invalidateOptionsMenu();
            return;
        }
        String dia = getIntent() != null ? getIntent().getStringExtra(EXTRA_DIA) : null;
        web.loadUrl(dia != null && dia.matches("\\d{4}-\\d{2}-\\d{2}") && !dia.equals(Datos.hoy()) ? e + "&d=" + dia : e);
        ir(0);
    }

    /** 1.6: sin barra de título; el menú está en «⋯ Más» de la barra de abajo. */
    private void menuMas(View ancla) {
        PopupMenu pm = new PopupMenu(this, ancla);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Actualizar");
        m.add(0, 4, 1, "Aviso para fichar en Séneca").setCheckable(true).setChecked(Fichar.activo(this));
        m.add(0, 2, 2, "Cambiar el enlace");
        m.add(0, 3, 3, "Cómo poner el widget");
        pm.setOnMenuItemClickListener(this::opcion);
        pm.show();
    }

    private boolean opcion(MenuItem it) {
        switch (it.getItemId()) {
            case 4: {
                boolean on = !Fichar.activo(this);
                Fichar.activar(this, on);
                new AlertDialog.Builder(this).setTitle("Aviso para fichar en Séneca")
                        .setMessage(on ? "Activado: 5 minutos antes de tu primera actividad y al acabar la última, el móvil vibrará, pitará dos veces y te recordará fichar. Podrás posponerlo 2 minutos o cerrarlo. No avisa en fines de semana, festivos ni si estás de baja."
                                : "Desactivado: ya no te recordará fichar.")
                        .setPositiveButton("Vale", null).show();
                if (on) HorarioWidget.refrescar(this);
                return true;
            }
            case 1:
                if (Datos.enlace(this) != null) { vista(actual).reload(); HorarioWidget.refrescar(this); }
                return true;
            case 2:
                campo.setText("");
                config.setVisibility(View.VISIBLE);
                barra.setVisibility(View.GONE);
                web.setVisibility(View.GONE);
                webTar.setVisibility(View.GONE);
                webSer.setVisibility(View.GONE);
                return true;
            case 3:
                new AlertDialog.Builder(this).setTitle("Poner el widget")
                        .setMessage(getString(R.string.como_widget)).setPositiveButton("Vale", null).show();
                return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (config.getVisibility() == View.VISIBLE) {
            if (Datos.enlace(this) != null) ir(actual); else super.onBackPressed();
            return;
        }
        final WebView w = vista(actual);
        if (actual == 0) {
            if (w.canGoBack()) w.goBack(); else super.onBackPressed();
            return;
        }
        // Tareas/Servicios: primero el «←» de la propia app web; si no hay, la pantalla Día
        w.evaluateJavascript("(function(){try{return !!(window.mvAtras&&window.mvAtras())}catch(e){return false}})()", r -> {
            if ("true".equals(r)) return;
            if (w.canGoBack()) w.goBack(); else ir(0);
        });
    }

    /* ---------------------------------------------------------------------------------------- */
    /*  Descargas de la app web (Word de actas, adjuntos, CSV…): se guardan en «Descargas».     */
    /* ---------------------------------------------------------------------------------------- */
    class Puente {
        @JavascriptInterface
        public void guardar(String nombre, String b64, String mime) {
            runOnUiThread(() -> guardarArchivo(nombre, b64, mime));
        }
    }

    private void guardarArchivo(String nombre, String b64, String mime) {
        if (nombre == null || nombre.trim().isEmpty()) nombre = "archivo";
        nombre = nombre.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        try {
            byte[] datos = Base64.decode(b64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new IllegalStateException("sin sitio");
                try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(datos); }
                abrirArchivo(u, mime, nombre);
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendiente = new String[]{nombre, b64, mime};
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISO_GUARDAR);
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, nombre);
                for (int n = 1; f.exists(); n++) {
                    int p = nombre.lastIndexOf('.');
                    f = new File(dir, p > 0 ? nombre.substring(0, p) + " (" + n + ")" + nombre.substring(p) : nombre + " (" + n + ")");
                }
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(datos); }
                final String m = mime, nom = f.getName();
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime},
                        (path, uri) -> runOnUiThread(() -> abrirArchivo(uri, m, nom)));
            }
        } catch (Exception e) {
            Toast.makeText(this, "No se ha podido guardar el archivo", Toast.LENGTH_LONG).show();
        }
    }

    private void abrirArchivo(Uri u, String mime, String nombre) {
        Toast.makeText(this, "Guardado en Descargas: " + nombre, Toast.LENGTH_LONG).show();
        if (u == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // no hay ninguna app para abrirlo: queda en Descargas
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == PERMISO_GUARDAR && pendiente != null) {
            String[] p = pendiente;
            pendiente = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) guardarArchivo(p[0], p[1], p[2]);
            else Toast.makeText(this, "Sin permiso no se puede guardar en Descargas", Toast.LENGTH_LONG).show();
            return;
        }
        super.onRequestPermissionsResult(req, perms, res);
    }
}
