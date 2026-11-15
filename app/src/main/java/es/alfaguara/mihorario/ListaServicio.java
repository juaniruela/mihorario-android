package es.alfaguara.mihorario;

import android.content.Context;
import android.content.Intent;
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
            } else {
                v = new RemoteViews(c.getPackageName(), R.layout.fila);
                String t = f.optString("t"), s = f.optString("s"), cl = f.optString("c");
                v.setTextViewText(R.id.ft, t);
                v.setTextViewText(R.id.fs, s);
                v.setViewVisibility(R.id.fs, s.isEmpty() ? View.GONE : View.VISIBLE);
                int fondo = "ahora".equals(cl) || "mio".equals(cl) ? R.color.fila_ahora : "rojo".equals(cl) && f.optBoolean("alt") ? R.color.rojo_fondo
                        : f.optBoolean("alt") ? R.color.fila_a : R.color.fila_b;
                v.setInt(R.id.fila_raiz, "setBackgroundResource", fondo);
                int texto = "rojo".equals(cl) ? c.getColor(R.color.rojo) : "pasada".equals(cl) ? c.getColor(R.color.suave) : c.getColor(R.color.texto);
                v.setTextColor(R.id.ft, texto);
                v.setTextColor(R.id.fs, "rojo".equals(cl) ? c.getColor(R.color.rojo) : c.getColor(R.color.suave));
            }
            // tocar cualquier fila abre la ventana de Horarios en ese día
            v.setOnClickFillInIntent(R.id.fila_raiz, new Intent());
            return v;
        }
    }
}
