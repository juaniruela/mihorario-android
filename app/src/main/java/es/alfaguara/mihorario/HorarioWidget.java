package es.alfaguara.mihorario;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Widget «Mi horario»: la clase de ahora (o la próxima), las clases de hoy, tareas y coche.
 * Solo lectura. Se actualiza cada 15 minutos aproximadamente, al pulsar ⟳ y al abrir la app.
 * Al tocarlo se abre la ventana de Horarios.
 */
public class HorarioWidget extends AppWidgetProvider {
    static final String ACT_REFRESCAR = "es.alfaguara.mihorario.REFRESCAR";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        programar(c);
        actualizar(c, goAsync());
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (ACT_REFRESCAR.equals(i.getAction())) {
            pintar(c, Datos.ultimoJson(c), "Actualizando…");
            actualizar(c, goAsync());
            return;
        }
        super.onReceive(c, i);
    }

    @Override
    public void onEnabled(Context c) { programar(c); }

    @Override
    public void onDisabled(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(intentRefrescar(c));
    }

    /** Pide al widget que se actualice (desde la app, al guardar el enlace o al abrirla). */
    static void refrescar(Context c) {
        Intent i = new Intent(c, HorarioWidget.class).setAction(ACT_REFRESCAR);
        c.sendBroadcast(i);
    }

    private static PendingIntent intentRefrescar(Context c) {
        Intent i = new Intent(c, HorarioWidget.class).setAction(ACT_REFRESCAR);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Actualización periódica (inexacta, para no gastar batería): cada 15 minutos. */
    private static void programar(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, intentRefrescar(c));
    }

    private static void actualizar(final Context c, final PendingResult pr) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String json = Datos.ultimoJson(app), error = null;
            if (Datos.enlace(app) == null) error = "Abre la app y pon tu enlace";
            else {
                try { json = Datos.descargar(app); }
                catch (Exception e) { error = e.getMessage() != null && e.getMessage().startsWith("Enlace") ? e.getMessage() : "Sin conexión"; }
            }
            pintar(app, json, error);
            if (pr != null) pr.finish();
        }).start();
    }

    private static String s(JSONObject o, String k) {
        if (o == null) return "";
        String v = o.optString(k, "");
        return "null".equals(v) ? "" : v.trim();
    }

    /** Dibuja el widget con los últimos datos. $aviso: texto pequeño en el pie (error o «Actualizando…»). */
    static void pintar(Context c, String json, String aviso) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_horario);
        JSONObject o = null;
        try { if (json != null) o = new JSONObject(json); } catch (Exception ignored) {}

        String cab = "Mi horario";
        String grande, sig = "", hoy = "", pie = "";
        if (o == null) {
            grande = Datos.enlace(c) == null ? "Toca aquí y pega tu enlace del widget" : "Cargando…";
        } else {
            String dia = s(o, "dia"), fecha = s(o, "fecha");
            if (fecha.length() == 10) fecha = fecha.substring(8, 10) + "/" + fecha.substring(5, 7);
            cab = (dia.isEmpty() ? "" : Character.toUpperCase(dia.charAt(0)) + dia.substring(1) + " " + fecha).trim();
            String sinClase = s(o, "sin_clase"), ahora = s(o, "clase_ahora"), prox = s(o, "clase_proxima");
            if (!sinClase.isEmpty()) grande = sinClase;
            else if (!ahora.isEmpty()) { grande = "▶ " + ahora; if (!prox.isEmpty()) sig = "Después: " + prox; }
            else if (!prox.isEmpty()) grande = "Próxima: " + prox;
            else grande = s(o, "texto_clases");
            hoy = s(o, "texto_hoy");
            if (hoy.equals(grande) || hoy.equals(sinClase)) hoy = "";
            int fuera = o.optInt("tareas_fuera", 0), thoy = o.optInt("tareas_hoy", 0);
            StringBuilder p = new StringBuilder();
            if (fuera > 0) p.append("⚠ ").append(fuera).append(fuera == 1 ? " tarea vencida" : " tareas vencidas");
            if (thoy > 0) { if (p.length() > 0) p.append(" · "); p.append(thoy).append(thoy == 1 ? " tarea hoy" : " tareas hoy"); }
            String coche = s(o, "texto_coche");
            if (!coche.isEmpty()) { if (p.length() > 0) p.append("\n"); p.append("🚗 ").append(coche.split("\n")[0]); }
            pie = p.toString();
        }
        long t = Datos.cuando(c);
        String act = t > 0 ? "Act. " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)) : "";
        String estado = aviso != null ? aviso + (act.isEmpty() ? "" : " · " + act) : act;

        v.setTextViewText(R.id.tvCab, cab);
        v.setTextViewText(R.id.tvGrande, grande);
        v.setTextViewText(R.id.tvSig, sig);
        v.setViewVisibility(R.id.tvSig, sig.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.tvHoy, hoy);
        v.setViewVisibility(R.id.tvHoy, hoy.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.tvPie, pie);
        v.setViewVisibility(R.id.tvPie, pie.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.tvEstado, estado);

        Intent abrir = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pa = PendingIntent.getActivity(c, 2, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.raiz, pa);
        v.setOnClickPendingIntent(R.id.btRefrescar, intentRefrescar(c));

        AppWidgetManager m = AppWidgetManager.getInstance(c);
        m.updateAppWidget(new ComponentName(c, HorarioWidget.class), v);
    }
}
