package es.alfaguara.mihorario;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ventana de Horarios (la misma página del enlace privado, dentro de la app) y configuración del enlace.
 * Solo consulta: para hacer más cosas, el botón «Abrir la app» de la página abre la web en el navegador.
 */
public class MainActivity extends Activity {
    static final String EXTRA_DIA = "dia";
    private WebView web;
    /** Pantalla para pegar el enlace. 1.1: es el ScrollView entero (antes quedaba encima de la ventana y no dejaba pulsar las pestañas). */
    private View config;
    private EditText campo;
    private ProgressBar carga;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);
        config = findViewById(R.id.config);
        campo = findViewById(R.id.campo);
        carga = findViewById(R.id.carga);
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

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                // la ventana se queda dentro; el resto («Abrir la app») va al navegador
                if (u.getPath() != null && u.getPath().endsWith("widget.php")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) { carga.setVisibility(View.VISIBLE); }
            @Override
            public void onPageFinished(WebView v, String url) { carga.setVisibility(View.GONE); }
        });

        if (!recibir(getIntent())) mostrar();
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
        HorarioWidget.refrescar(this);
        Toast.makeText(this, "Enlace guardado. Ya puedes añadir el widget a la pantalla de inicio.", Toast.LENGTH_LONG).show();
        mostrar();
    }

    private void mostrar() {
        String e = Datos.enlace(this);
        boolean hay = e != null;
        config.setVisibility(hay ? View.GONE : View.VISIBLE);
        web.setVisibility(hay ? View.VISIBLE : View.GONE);
        if (hay) {
            // 1.1: desde el widget se abre en el día que tenga elegido
            String dia = getIntent() != null ? getIntent().getStringExtra(EXTRA_DIA) : null;
            web.loadUrl(dia != null && dia.matches("\\d{4}-\\d{2}-\\d{2}") && !dia.equals(Datos.hoy()) ? e + "&d=" + dia : e);
        }
        else carga.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Actualizar");
        m.add(0, 2, 1, "Cambiar el enlace");
        m.add(0, 3, 2, "Cómo poner el widget");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case 1:
                if (Datos.enlace(this) != null) { web.reload(); HorarioWidget.refrescar(this); }
                return true;
            case 2:
                campo.setText("");
                config.setVisibility(View.VISIBLE);
                web.setVisibility(View.GONE);
                return true;
            case 3:
                new AlertDialog.Builder(this).setTitle("Poner el widget")
                        .setMessage(getString(R.string.como_widget)).setPositiveButton("Vale", null).show();
                return true;
        }
        return super.onOptionsItemSelected(it);
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else if (config.getVisibility() == View.VISIBLE && Datos.enlace(this) != null) mostrar();
        else super.onBackPressed();
    }
}
