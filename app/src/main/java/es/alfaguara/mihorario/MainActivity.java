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
import android.view.MotionEvent;
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
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private WebView web, webTar, webSer, webCen;
    /** Pantalla para pegar el enlace. */
    private View config, barra;
    private EditText campo;
    private ProgressBar carga;
    private TextView tabDia, tabTar, tabSer, tabCen;
    private int actual = 0;            // 0 Día · 1 Tareas · 2 Servicios
    private final boolean[] cargada = new boolean[4];
    private ValueCallback<Uri[]> subida;
    /** 1.7: botón flotante FlashWork (1.8: en todas las pantallas y se puede arrastrar). */
    private View fab;
    private boolean fwPendiente = false, busPendiente = false;
    private int fwOrigen = -1;
    static final String TRIBBU = "com.hoopcarpool";
    private String[] pendiente;        // descarga a la espera del permiso (Android 8–9)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        webTar = findViewById(R.id.webTar);
        webSer = findViewById(R.id.webSer);
        webCen = findViewById(R.id.webCen);
        config = findViewById(R.id.config);
        barra = findViewById(R.id.barra);
        fab = findViewById(R.id.fabs);    // 1.9: el bloque con 🔍 y FlashWork
        findViewById(R.id.fab).setOnClickListener(x -> flashwork());
        findViewById(R.id.fabBus).setOnClickListener(x -> buscar());
        fabMovible();
        campo = findViewById(R.id.campo);
        carga = findViewById(R.id.carga);
        tabDia = findViewById(R.id.tabDia);
        tabTar = findViewById(R.id.tabTar);
        tabSer = findViewById(R.id.tabSer);
        tabCen = findViewById(R.id.tabCen);
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
        preparar(webCen, true);
        tabDia.setOnClickListener(x -> ir(0));
        tabTar.setOnClickListener(x -> ir(1));
        tabSer.setOnClickListener(x -> ir(2));
        tabCen.setOnClickListener(x -> ir(3));
        findViewById(R.id.tabMas).setOnClickListener(this::menuMas);
        // 1.6: permiso de notificaciones (Android 13+) para el aviso de fichar en Séneca
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 43);

        if (!recibir(getIntent())) mostrar();
        tribbuDesdeWidget(getIntent());
    }

    /* ---------------------------------------------------------------------------------------- */
    /*  1.8: el botón FlashWork se arrastra a cualquier sitio (se recuerda la posición).          */
    /* ---------------------------------------------------------------------------------------- */
    private void fabMovible() {
        final float d = getResources().getDisplayMetrics().density;
        final float[] ini = new float[4];
        final boolean[] mov = {false};
        // 1.9: se arrastra desde cualquiera de los dos botones; mueve el bloque entero
        View.OnTouchListener arrastre = (b, e) -> {
            View v = fab, p = (View) fab.getParent();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ini[0] = e.getRawX(); ini[1] = e.getRawY(); ini[2] = v.getX(); ini[3] = v.getY();
                    mov[0] = false;
                    b.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - ini[0], dy = e.getRawY() - ini[1];
                    if (!mov[0] && Math.hypot(dx, dy) < 10 * d) return true;
                    if (!mov[0]) { mov[0] = true; b.setPressed(false); v.setAlpha(.85f); }
                    v.setX(Math.max(0, Math.min(p.getWidth() - v.getWidth(), ini[2] + dx)));
                    v.setY(Math.max(0, Math.min(p.getHeight() - v.getHeight(), ini[3] + dy)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    b.setPressed(false);
                    if (!mov[0]) { b.performClick(); return true; }
                    v.setAlpha(1f);
                    float rx = p.getWidth() - v.getWidth(), ry = p.getHeight() - v.getHeight();
                    Datos.prefs(this).edit().putFloat("fab_x", rx > 0 ? v.getX() / rx : 1f).putFloat("fab_y", ry > 0 ? v.getY() / ry : 1f).apply();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    b.setPressed(false); v.setAlpha(1f);
                    return true;
            }
            return false;
        };
        findViewById(R.id.fab).setOnTouchListener(arrastre);
        findViewById(R.id.fabBus).setOnTouchListener(arrastre);
        // al cambiar el tamaño (girar el móvil, teclado…) se vuelve a colocar en su sitio
        ((View) fab.getParent()).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> colocarFab());
    }

    private void colocarFab() {
        android.content.SharedPreferences sp = Datos.prefs(this);
        if (!sp.contains("fab_x")) return;          // sin mover: abajo a la derecha
        View p = (View) fab.getParent();
        float rx = p.getWidth() - fab.getWidth(), ry = p.getHeight() - fab.getHeight();
        if (rx <= 0 || ry <= 0) return;
        fab.setX(Math.max(0, Math.min(rx, sp.getFloat("fab_x", 1f) * rx)));
        fab.setY(Math.max(0, Math.min(ry, sp.getFloat("fab_y", 1f) * ry)));
    }

    /** 1.8: «Abrir Tribbu» desde una fila de turno del widget. 1.9: también abrir una pantalla (alarma de tarea → Tareas). */
    private void tribbuDesdeWidget(Intent i) {
        if (i != null && i.getBooleanExtra("iseneca", false)) { i.removeExtra("iseneca"); abrirISeneca(); return; }
        if (i != null && i.hasExtra("pantalla")) { int n = i.getIntExtra("pantalla", 0); i.removeExtra("pantalla"); if (Datos.enlace(this) != null) ir(n == 0 ? 3 : n); }
        if (i == null || !i.hasExtra("tb_t")) return;
        String t = i.getStringExtra("tb_t"), sd = i.getStringExtra("tb_s");
        boolean cond = i.getBooleanExtra("tb_c", false);
        i.removeExtra("tb_t");
        abrirTribbu(t, sd, cond);
    }

    /** Ajustes comunes de las tres WebView. app=true: Tareas/Servicios (la app web completa). */
    private void preparar(WebView w, boolean app) {
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(app ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        ws.setMediaPlaybackRequiresUserGesture(true);
        // 1.7: el servidor reconoce la app (nunca se entra como Administrador ni Directiva)
        ws.setUserAgentString(ws.getUserAgentString() + " MiHorarioApp/6.08");
        if (app) w.addJavascriptInterface(new Puente(), "AppMovil");
        if (!app || w == webCen) w.addJavascriptInterface(new Dia(), "AppDia");   // 6.08: Semana y Consultar van dentro de Centro
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;   // 6.08: los marcos (Semana/Consultar de Centro) se quedan dentro
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
            public void onPageFinished(WebView v, String url) {
                carga.setVisibility(View.GONE);
                if (v == webTar && fwPendiente) { fwPendiente = false; lanzarFw(); }
                if (v == webTar && busPendiente) { busPendiente = false; lanzarBus(webTar); }
            }
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

    private WebView vista(int i) { return i == 1 ? webTar : i == 2 ? webSer : i == 3 ? webCen : web; }

    /** 6.08: clave del enlace del widget (k=…), para Semana y Consultar dentro de Centro. */
    private String clave() {
        String e = Datos.enlace(this);
        try { String k = e == null ? null : Uri.parse(e).getQueryParameter("k"); return k == null ? "" : k; } catch (Exception x) { return ""; }
    }

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
        webCen.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
        config.setVisibility(View.GONE);
        barra.setVisibility(View.VISIBLE);
        fab.setVisibility(View.VISIBLE);   // 1.8: en Día, Tareas y Servicios
        TextView[] T = {tabDia, tabTar, tabSer, tabCen};
        for (int k = 0; k < 4; k++) {
            T[k].setAlpha(k == i ? 1f : .6f);
            T[k].setTypeface(null, k == i ? Typeface.BOLD : Typeface.NORMAL);
            T[k].setBackgroundColor(k == i ? 0x33FFFFFF : 0);
        }
        if (i > 0 && !cargada[i]) {
            String b = baseApp();
            if (b != null) { vista(i).loadUrl(b + (i == 1 ? "?movil=tareas" : i == 3 ? "?movil=centro&wk=" + Uri.encode(clave()) : "?movil=servicios")); cargada[i] = true; }
        }
        invalidateOptionsMenu();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (!recibir(i)) mostrar();
        tribbuDesdeWidget(i);
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
        cargada[1] = cargada[2] = cargada[3] = false;
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
            webCen.setVisibility(View.GONE);
            carga.setVisibility(View.GONE);
            fab.setVisibility(View.GONE);
            invalidateOptionsMenu();
            return;
        }
        // 6.08: sin pantalla Día: la app empieza en Centro (Semana, Horarios → Consultar, Ahora, Coches…)
        ir(3);
    }

    /** 1.6: sin barra de título; el menú está en «⋯ Más» de la barra de abajo. */
    private void menuMas(View ancla) {
        PopupMenu pm = new PopupMenu(this, ancla);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Actualizar");
        m.add(0, 7, 1, "Enviar ahora la agenda");   // 1.11: el aviso de fichar va en Mi cuenta → Configuración de avisos
        m.add(0, 5, 2, "Abrir la app web");
        m.add(0, 6, 3, "Trayectos de Tribbu (conductor)");
        m.add(0, 2, 4, "Cambiar el enlace");
        m.add(0, 3, 5, "Cómo poner el widget");
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
            case 7:
                Agenda.enviarAhora(this);
                Toast.makeText(this, "Preparando la agenda…", Toast.LENGTH_SHORT).show();
                return true;
            case 5: {
                String b = baseApp();
                if (b == null) { Toast.makeText(this, "Primero guarda el enlace de tu widget", Toast.LENGTH_LONG).show(); return true; }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(b))); }
                catch (Exception e) { Toast.makeText(this, "No hay ningún navegador para abrirla", Toast.LENGTH_LONG).show(); }
                return true;
            }
            case 6:
                configTribbu();
                return true;
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
                webCen.setVisibility(View.GONE);
                fab.setVisibility(View.GONE);
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
        if (actual == 0 || actual == 3) {
            if (w.canGoBack()) w.goBack(); else super.onBackPressed();
            return;
        }
        // Tareas/Servicios: primero el «←» de la propia app web; si no hay, la pantalla Día
        w.evaluateJavascript("(function(){try{return !!(window.mvAtras&&window.mvAtras())}catch(e){return false}})()", r -> {
            if ("true".equals(r)) return;
            if (w.canGoBack()) w.goBack(); else ir(3);
        });
    }

    /* ---------------------------------------------------------------------------------------- */
    /*  1.7: FlashWork desde la pantalla Día: abre la ventana de FlashWork de Tareas y, al cerrarla, vuelve.  */
    /* ---------------------------------------------------------------------------------------- */
    private void flashwork() {
        if (Datos.enlace(this) == null) return;
        fwOrigen = actual;
        boolean yaCargada = cargada[1];
        ir(1);
        if (!yaCargada || webTar.getProgress() < 100) fwPendiente = true; else lanzarFw();
    }

    /** Espera a que la app web tenga la sesión iniciada (si hay que entrar, después de entrar) y abre FlashWork. */
    private void lanzarFw() {
        webTar.evaluateJavascript("(function(){var n=0;function f(){n++;try{if(typeof ME!=='undefined'&&ME&&typeof tareaExpres==='function'){"
                + "if(typeof usaTareas==='function'&&!usaTareas()){if(window.AppMovil)AppMovil.finFlash(false);return}"
                + "if(!document.getElementById('txOv'))tareaExpres();"
                + "var w=setInterval(function(){if(!document.getElementById('txOv')){clearInterval(w);if(window.AppMovil)AppMovil.finFlash(true)}},400);return}}catch(e){}"
                + "if(n<600)setTimeout(f,300)}f()})()", null);
    }

    /* 1.9: buscador (alumnos, profesores, grupos, agenda e «ir …» a las pantallas de la app) */
    private void buscar() {
        if (Datos.enlace(this) == null) return;
        if (actual == 2 && cargada[2]) { lanzarBus(webSer); return; }
        if (actual == 3 && cargada[3]) { lanzarBus(webCen); return; }
        boolean yaCargada = cargada[1];
        ir(1);
        if (!yaCargada || webTar.getProgress() < 100) busPendiente = true; else lanzarBus(webTar);
    }

    private void lanzarBus(WebView w) {
        w.evaluateJavascript("(function(){var n=0;function f(){n++;try{if(typeof ME!=='undefined'&&ME&&typeof busAbrir==='function'){busAbrir();return}}catch(e){}"
                + "if(n<600)setTimeout(f,300)}f()})()", null);
    }

    /** 1.9: «ir …» del buscador: cambia de pantalla y, si ya estaba cargada, hace lo que se pidió (sección de Tareas o Servicios). */
    private void irDesdeWeb(int n) {
        if (n == 0) n = 3;   // 6.08: ya no hay pantalla Día
        if (n < 0 || n > 3) return;
        boolean yaCargada = n > 0 && cargada[n] && vista(n).getProgress() >= 100;
        ir(n);
        if (yaCargada) vista(n).evaluateJavascript("(function(){try{if(window.mvIrPend)mvIrPend()}catch(e){}})()", null);
    }

    private void finFlash(boolean ok) {
        if (!ok) Toast.makeText(this, "Tu usuario no tiene las tareas activadas", Toast.LENGTH_LONG).show();
        int o = fwOrigen;
        fwOrigen = -1;
        if (o >= 0 && o != 1 && actual == 1) ir(o);
    }

    /* ---------------------------------------------------------------------------------------- */
    /*  1.7: Tribbu desde los turnos de la pantalla Día.                                          */
    /* ---------------------------------------------------------------------------------------- */
    class Dia {
        /** 1.10: botón iSéneca de la primera y la última actividad de la mañana. */
        @JavascriptInterface
        public void iseneca() { runOnUiThread(MainActivity.this::abrirISeneca); }

        @JavascriptInterface
        public void tribbu(String tipo, String sede, boolean conductor) {
            runOnUiThread(() -> abrirTribbu(tipo, sede, conductor));
        }
    }

    /** 1.10: abre la app iSéneca (o Google Play para instalarla). */
    private void abrirISeneca() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Fichar.ISENECA);
        if (i != null) { startActivity(i); return; }
        Toast.makeText(this, "iSéneca no está instalada: te llevo a Google Play", Toast.LENGTH_LONG).show();
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Fichar.ISENECA))); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + Fichar.ISENECA))); } catch (Exception ignored) {}
        }
    }

    static String clave(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /**
     * Conductor/a: a primera hora (Entrada) el trayecto «Ir al trabajo»; a la salida, el de vuelta a su sede (Santa Fe o Aurgi).
     * Si ha guardado el enlace de ese trayecto de Tribbu, se abre directamente; si no, abre Tribbu y le recuerda qué elegir.
     */
    private void abrirTribbu(String tipo, String sede, boolean conductor) {
        boolean ida = !"S".equals(tipo);
        if (conductor) {
            String k = ida ? "tribbu_ida" : "tribbu_vuelta_" + clave(sede);
            String enlace = Datos.prefs(this).getString(k, "");
            if (!enlace.isEmpty()) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(enlace));
                    if (getPackageManager().getLaunchIntentForPackage(TRIBBU) != null) i.setPackage(TRIBBU);
                    startActivity(i);
                    return;
                } catch (Exception e) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(enlace))); return; } catch (Exception ignored) {}
                }
            }
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(TRIBBU);
        if (i == null) {
            Toast.makeText(this, "Tribbu no está instalada: te llevo a Google Play", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TRIBBU))); }
            catch (Exception e) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + TRIBBU))); } catch (Exception ignored) {}
            }
            return;
        }
        startActivity(i);
        if (conductor) {
            String d = ida ? "elige «Ir al trabajo»" : "elige como destino " + (sede == null || sede.isEmpty() ? "tu sede" : sede);
            Toast.makeText(this, "En Tribbu, " + d + ". Consejo: guarda el enlace de ese trayecto en ⋯ Más → Trayectos de Tribbu y se abrirá directamente.", Toast.LENGTH_LONG).show();
        }
    }

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Enlaces de los trayectos publicados en Tribbu («Compartir» del trayecto): ida al trabajo y vuelta a cada sede. */
    private void configTribbu() {
        String[][] C = {{"tribbu_ida", "Ir al trabajo (entrada, primera hora)"},
                {"tribbu_vuelta_santa_fe", "Vuelta a Santa Fe (salida)"},
                {"tribbu_vuelta_aurgi", "Vuelta a Aurgi (salida)"}};
        LinearLayout L = new LinearLayout(this);
        L.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        L.setPadding(p, p / 2, p, 0);
        TextView ex = new TextView(this);
        ex.setText("Si conduces, en Tribbu abre cada trayecto que tengas publicado, pulsa «Compartir» o «Añadir manualmente», copia el enlace y pégalo aquí. "
                + "Así el botón «Abrir Tribbu» de tus turnos abre directamente ese trayecto. Si lo dejas vacío, se abre Tribbu y te dice qué elegir.");
        ex.setTextSize(13);
        L.addView(ex);
        EditText[] E = new EditText[C.length];
        for (int k = 0; k < C.length; k++) {
            TextView t = new TextView(this);
            t.setText(C[k][1]);
            t.setTextSize(13);
            t.setTypeface(null, Typeface.BOLD);
            t.setPadding(0, p / 2, 0, 0);
            L.addView(t);
            E[k] = new EditText(this);
            E[k].setHint("https://…");
            E[k].setSingleLine(true);
            E[k].setTextSize(13);
            E[k].setText(Datos.prefs(this).getString(C[k][0], ""));
            L.addView(E[k]);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(L);
        new AlertDialog.Builder(this).setTitle("Trayectos de Tribbu").setView(sv)
                .setPositiveButton("Guardar", (d, w) -> {
                    android.content.SharedPreferences.Editor ed = Datos.prefs(this).edit();
                    for (int k = 0; k < C.length; k++) {
                        Matcher m = URL.matcher(E[k].getText().toString());
                        ed.putString(C[k][0], m.find() ? m.group() : "");
                    }
                    ed.apply();
                    Toast.makeText(this, "Trayectos de Tribbu guardados", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    /* ---------------------------------------------------------------------------------------- */
    /*  Descargas de la app web (Word de actas, adjuntos, CSV…): se guardan en «Descargas».     */
    /* ---------------------------------------------------------------------------------------- */
    class Puente {
        @JavascriptInterface
        public void ir(int n) { runOnUiThread(() -> irDesdeWeb(n)); }

        /** 1.9: al guardar una tarea, se vuelven a pedir los datos (y se programan sus alarmas). */
        @JavascriptInterface
        public void refrescar() { HorarioWidget.refrescar(MainActivity.this); }

        @JavascriptInterface
        public void finFlash(boolean ok) {
            runOnUiThread(() -> MainActivity.this.finFlash(ok));
        }

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
