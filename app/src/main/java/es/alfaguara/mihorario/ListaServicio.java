package es.alfaguara.mihorario;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lista del widget (se desplaza si no cabe): un título con una línea encima por cada bloque
 * (Actividades, Turnos de coche, Tareas, De baja…) y sus filas con el fondo alternando.
 * Las filas las prepara HorarioWidget.pintar() y se guardan en las preferencias («filas»).
 */
public class ListaServicio extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Fabrica(getApplicationContext());
    }

    static class Fabrica implements RemoteViewsFactory {
        private final Context c;
        private JSONArray filas = new JSONArray();

        Fabrica(Context c) { this.c = c; }

        @Override public void onCreate() { cargar(); }
        @Override public void onDataSetChanged() { cargar(); }
        @Override public void onDestroy() {}
        @Override public int getCount() { return filas.length(); }
        @Override public RemoteViews getLoadingView() {
            // 1.3: nada de «Cargando…»: una fila vacía mientras se prepara
            return new RemoteViews(c.getPackageName(), R.layout.fila);
        }
        @Override public int getViewTypeCount() { return 2; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return false; }

        private void cargar() {
            try { filas = new JSONArray(Datos.prefs(c).getString("filas", "[]")); }
            catch (Exception e) { filas = new JSONArray(); }
        }

        @Override
        public RemoteViews getViewAt(int i) {
            JSONObject f = filas.optJSONObject(i);
            if (f == null) f = new JSONObject();
            RemoteViews v;
            if ("tit".equals(f.optString("k"))) {
                v = new RemoteViews(c.getPackageName(), R.layout.fila_titulo);
                v.setTextViewText(R.id.titulo, f.optString("t"));
                boolean rojo = f.optBoolean("rojo", false);
                int col = c.getColor(rojo ? R.color.rojo : R.color.separador);
                v.setTextColor(R.id.titulo, col);
                v.setInt(R.id.linea, "setBackgroundColor", col);
                // 6.11: «De guardia a las…»: icono al final de la línea que abre la app de guardias en una ventana flotante
                String app = f.optString("app");
                if (app.startsWith("https://")) {
                    v.setViewVisibility(R.id.tbApp, View.VISIBLE);
                    v.setOnClickFillInIntent(R.id.tbApp, new Intent().putExtra("flotante", app));
                } else v.setViewVisibility(R.id.tbApp, View.GONE);
            } else {
                v = new RemoteViews(c.getPackageName(), R.layout.fila);
                String t = f.optString("t"), s = f.optString("s"), cl = f.optString("c");
                v.setTextViewText(R.id.ft, t);
                v.setTextViewText(R.id.fs, s);
                v.setViewVisibility(R.id.fs, s.isEmpty() ? View.GONE : View.VISIBLE);
                int fondo = "sig".equals(cl) ? R.color.fila_sig : "ahora".equals(cl) ? R.drawable.fondo_ahora : "mio".equals(cl) ? R.color.fila_ahora : "rojo".equals(cl) && f.optBoolean("alt") ? R.color.rojo_fondo
                        : f.optBoolean("alt") ? R.color.fila_a : R.color.fila_b;
                v.setInt(R.id.fila_raiz, "setBackgroundResource", fondo);
                int texto = "rojo".equals(cl) ? c.getColor(R.color.rojo) : "pasada".equals(cl) ? c.getColor(R.color.suave) : c.getColor(R.color.texto);
                if ("ahora".equals(cl)) {
                    // 6.09: la actividad de ahora se ve de un vistazo: fondo crema y texto ámbar oscuro en negrita
                    SpannableString sp = new SpannableString(t);
                    sp.setSpan(new StyleSpan(Typeface.BOLD), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    v.setTextViewText(R.id.ft, sp);
                    texto = c.getColor(R.color.ahora_txt);
                }
                v.setTextColor(R.id.ft, texto);
                v.setTextColor(R.id.fs, "rojo".equals(cl) ? c.getColor(R.color.rojo) : c.getColor(R.color.suave));
            }
            // 1.11: tocar una fila ya no abre la app (solo el botón de arriba); sí los botones Tribbu / iSéneca
            // 1.8: en los turnos de coche, botón «Abrir Tribbu» (conductor: ir al trabajo / destino su sede)
            if (!"tit".equals(f.optString("k"))) {
                JSONObject tb = f.optJSONObject("tb");
                String is = f.optString("is");
                if (!is.isEmpty()) {
                    // 1.10: primera y última actividad de la mañana: botón iSéneca (para fichar)
                    v.setTextViewText(R.id.ftb, "◉ iSéneca" + ("E".equals(is) ? " · entrada" : "S".equals(is) ? " · salida" : ""));
                    v.setInt(R.id.ftb, "setBackgroundResource", R.drawable.fondo_iseneca);
                    v.setViewVisibility(R.id.ftb, View.VISIBLE);
                    v.setOnClickFillInIntent(R.id.ftb, new Intent().putExtra("iseneca", true));
                } else if (tb != null) {
                    v.setInt(R.id.ftb, "setBackgroundResource", R.drawable.fondo_tribbu);
                    boolean cond = tb.optInt("c", 0) == 1;
                    String tt = tb.optString("t"), sd = tb.optString("s");
                    v.setTextViewText(R.id.ftb, "🚙 Abrir Tribbu" + (cond ? " · " + ("S".equals(tt) ? "destino " + (sd.isEmpty() ? "vuelta" : sd) : "ir al trabajo") : ""));
                    v.setViewVisibility(R.id.ftb, View.VISIBLE);
                    v.setOnClickFillInIntent(R.id.ftb, new Intent().putExtra("tb_t", tt).putExtra("tb_s", sd).putExtra("tb_c", cond));
                } else v.setViewVisibility(R.id.ftb, View.GONE);
            }
            return v;
        }
    }
}
