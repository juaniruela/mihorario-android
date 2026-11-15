package es.alfaguara.mihorario;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Widget «Mi horario»: el día elegido (‹ anterior · siguiente ›) tal y como lo enseña «Horarios» en la app web;
 * arriba, los avisos del día en una línea que se desplaza. Debajo, una lista que se desplaza: actividades, turnos de coche,
 * tareas y quién está de baja, con una línea entre bloques y las filas alternando el color. Solo lectura.
 * Se actualiza solo a menos diez y a y veinte de cada hora, al empezar y acabar cada clase de hoy, a medianoche,
 * al pulsar ⟳ y al abrir la app. Al tocarlo se abre la ventana de Horarios en ese día.
 */
public class HorarioWidget extends AppWidgetProvider {
    static final String ACT_REFRESCAR = "es.alfaguara.mihorario.REFRESCAR";
    static final String ACT_AUTO = "es.alfaguara.mihorario.AUTO";
    static final String ACT_ANTERIOR = "es.alfaguara.mihorario.ANTERIOR";
    static final String ACT_SIGUIENTE = "es.alfaguara.mihorario.SIGUIENTE";
    static final String ACT_HOY = "es.alfaguara.mihorario.HOY";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Datos.volverAHoySiToca(c);
        actualizar(c, goAsync());
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) { super.onReceive(c, i); return; }
        switch (a) {
            case ACT_ANTERIOR:
            case ACT_SIGUIENTE:
            case ACT_HOY: {
                String nuevo = ACT_HOY.equals(a) ? Datos.hoy() : Datos.sumar(Datos.sel(c), ACT_ANTERIOR.equals(a) ? -1 : 1);
                Datos.elegir(c, nuevo);
                pintar(c, Datos.ultimoJson(c), "Actualizando…");
                actualizar(c, goAsync());
                return;
            }
            case ACT_REFRESCAR:
                pintar(c, Datos.ultimoJson(c), "Actualizando…");
                actualizar(c, goAsync());
                return;
            case ACT_AUTO:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED":
                if (!hayWidgets(c)) return;
                Datos.volverAHoySiToca(c);
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
        if (am != null) am.cancel(pendiente(c, ACT_AUTO, 1));
    }

    private static boolean hayWidgets(Context c) {
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, HorarioWidget.class));
        return ids != null && ids.length > 0;
    }

    /** Pide al widget que se actualice (desde la app, al guardar el enlace o al abrirla). */
    static void refrescar(Context c) {
        c.sendBroadcast(new Intent(c, HorarioWidget.class).setAction(ACT_REFRESCAR));
    }

    private static PendingIntent pendiente(Context c, String accion, int codigo) {
        Intent i = new Intent(c, HorarioWidget.class).setAction(accion);
        return PendingIntent.getBroadcast(c, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ------------------------------------------------------------------ */
    /*  Actualizaciones automáticas: a menos diez y a y veinte de cada hora, */
    /*  al empezar y acabar cada clase de hoy y a medianoche.              */
    /* ------------------------------------------------------------------ */
    static long proximaHora(Context c, long ahora) {
        List<Long> L = new ArrayList<>();
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(ahora);
        k.set(Calendar.SECOND, 5);
        k.set(Calendar.MILLISECOND, 0);
        // :20 y :50 de esta hora y de la siguiente
        for (int h = 0; h <= 1; h++) for (int mm : new int[]{20, 50}) {
            Calendar x = (Calendar) k.clone();
            x.add(Calendar.HOUR_OF_DAY, h);
            x.set(Calendar.MINUTE, mm);
            L.add(x.getTimeInMillis());
        }
        // medianoche (para pasar al día nuevo)
        Calendar n = (Calendar) k.clone();
        n.add(Calendar.DAY_OF_MONTH, 1);
        n.set(Calendar.HOUR_OF_DAY, 0);
        n.set(Calendar.MINUTE, 1);
        L.add(n.getTimeInMillis());
        // inicio y fin de cada clase de hoy
        try {
            String js = Datos.ultimoJson(c);
            JSONObject o = js == null ? null : new JSONObject(js);
            if (o != null && Datos.hoy().equals(o.optString("fecha"))) {
                JSONArray cl = o.optJSONArray("clases");
                if (cl != null) for (int i = 0; i < cl.length(); i++) {
                    JSONObject x = cl.getJSONObject(i);
                    for (String campo : new String[]{"i", "f"}) {
                        String[] p = x.optString(campo, "").split(":");
                        if (p.length < 2) continue;
                        Calendar t = (Calendar) k.clone();
                        t.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
                        t.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
                        L.add(t.getTimeInMillis());
                    }
                }
            }
        } catch (Exception ignored) {}
        long mejor = Long.MAX_VALUE;
        for (long t : L) if (t > ahora + 30_000 && t < mejor) mejor = t;
        return mejor;
    }

    static void programar(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || !hayWidgets(c)) return;
        long t = proximaHora(c, System.currentTimeMillis());
        PendingIntent pi = pendiente(c, ACT_AUTO, 1);
        try {
            boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, pi);
        }
    }

    private static void actualizar(final Context c, final PendingResult pr) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String json = Datos.ultimoJson(app), error = null;
            try {
                if (Datos.enlace(app) == null) error = "Abre la app y pon tu enlace";
                else {
                    try { json = Datos.descargar(app); }
                    catch (Exception e) { error = e.getMessage() != null && e.getMessage().startsWith("Enlace") ? e.getMessage() : "Sin conexión"; }
                }
                pintar(app, json, error);
                programar(app);
            } finally {
                if (pr != null) pr.finish();
            }
        }).start();
    }

    private static String s(JSONObject o, String k) {
        if (o == null) return "";
        String v = o.optString(k, "");
        return "null".equals(v) ? "" : v.trim();
    }

    private static String fechaCorta(String iso) {
        return iso.length() == 10 ? iso.substring(8, 10) + "/" + iso.substring(5, 7) : iso;
    }

    private static String diaDe(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            String x = new SimpleDateFormat("EEEE", new Locale("es", "ES")).format(d);
            return Character.toUpperCase(x.charAt(0)) + x.substring(1);
        } catch (Exception e) { return ""; }
    }

    /** Dibuja el widget con los últimos datos. aviso: texto pequeño en el pie (error, «Cargando…»…). */
    static void pintar(Context c, String json, String aviso) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_horario);
        JSONObject o = null;
        try { if (json != null) o = new JSONObject(json); } catch (Exception ignored) {}

        String elegido = Datos.sel(c), hoyIso = Datos.hoy();
        JSONObject S = o != null ? o.optJSONObject("sel") : null;
        // los datos guardados son de otro día: mientras llegan los nuevos, solo la cabecera
        boolean valen = S != null ? elegido.equals(s(S, "fecha")) : (o != null && elegido.equals(s(o, "fecha")));

        String rel = elegido.equals(hoyIso) ? "Hoy" : elegido.equals(Datos.sumar(hoyIso, 1)) ? "Mañana" : elegido.equals(Datos.sumar(hoyIso, -1)) ? "Ayer" : "";
        String cab = diaDe(elegido) + " " + fechaCorta(elegido) + (rel.isEmpty() ? "" : " · " + rel);
        // 1.4: arriba, los avisos del día en una línea que se desplaza; debajo, la lista de bloques
        StringBuilder av = new StringBuilder();
        JSONArray filas = new JSONArray();

        if (o == null) {
            if (Datos.enlace(c) == null) av.append("Toca aquí y pega tu enlace del widget");
        } else if (!valen) {
            // los datos guardados son de otro día: se espera a los nuevos
        } else if (S == null) {
            // servidor antiguo (sin el día elegido): lo de siempre
            lineas(filas, "Hoy", s(o, "texto_hoy"));
        } else {
            JSONArray AV = S.optJSONArray("avisos");
            if (AV != null) for (int i = 0; i < AV.length(); i++) {
                String x = AV.optString(i, "").trim();
                if (x.isEmpty()) continue;
                if (av.length() > 0) av.append("     •     ");
                av.append(x);
            }
            JSONArray B = S.optJSONArray("bloques");
            if (B != null) {
                for (int b = 0; b < B.length(); b++) {
                    JSONObject bl = B.optJSONObject(b);
                    if (bl == null || "Aviso".equals(s(bl, "tit"))) continue;   // ya va en la línea de avisos
                    boolean rojo = bl.optBoolean("rojo", false);
                    JSONArray F = bl.optJSONArray("filas");
                    try {
                        filas.put(new JSONObject().put("k", "tit").put("t", s(bl, "tit")).put("rojo", rojo));
                        if (F != null) for (int i = 0; i < F.length(); i++) {
                            JSONObject f = F.optJSONObject(i);
                            if (f == null) continue;
                            filas.put(new JSONObject().put("k", "fila").put("t", s(f, "t")).put("s", s(f, "s"))
                                    .put("c", rojo ? "rojo" : s(f, "c")).put("alt", i % 2 == 0));
                        }
                    } catch (Exception ignored) {}
                }
            } else lineas(filas, "Este día", s(S, "texto"));
        }
        long t = Datos.cuando(c);
        String act = t > 0 ? "Act. " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t)) : "";
        String estado = aviso != null ? aviso + (act.isEmpty() ? "" : " · " + act) : act;

        v.setTextViewText(R.id.tvCab, cab);
        v.setTextViewText(R.id.tvAvisos, av.toString());
        v.setViewVisibility(R.id.tvAvisos, av.length() == 0 ? View.GONE : View.VISIBLE);
        // 1.2: lista con desplazamiento (ListaServicio la dibuja a partir de «filas»)
        Datos.prefs(c).edit().putString("filas", filas.toString()).apply();
        Intent srv = new Intent(c, ListaServicio.class);
        srv.setData(Uri.parse(srv.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.lista, srv);
        v.setEmptyView(R.id.lista, R.id.vacia);
        v.setViewVisibility(R.id.vacia, filas.length() == 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.tvEstado, estado);

        Intent abrir = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_DIA, elegido);
        PendingIntent pa = PendingIntent.getActivity(c, 2, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.raiz, pa);
        v.setOnClickPendingIntent(R.id.tvAvisos, pa);
        PendingIntent plantilla = PendingIntent.getActivity(c, 7, new Intent(abrir),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        v.setPendingIntentTemplate(R.id.lista, plantilla);
        v.setOnClickPendingIntent(R.id.btRefrescar, pendiente(c, ACT_REFRESCAR, 3));
        v.setOnClickPendingIntent(R.id.btAnterior, pendiente(c, ACT_ANTERIOR, 4));
        v.setOnClickPendingIntent(R.id.btSiguiente, pendiente(c, ACT_SIGUIENTE, 5));
        v.setOnClickPendingIntent(R.id.tvCab, pendiente(c, ACT_HOY, 6));

        AppWidgetManager m = AppWidgetManager.getInstance(c);
        ComponentName cn = new ComponentName(c, HorarioWidget.class);
        m.updateAppWidget(cn, v);
        m.notifyAppWidgetViewDataChanged(m.getAppWidgetIds(cn), R.id.lista);
    }

    /** Un bloque a partir de un texto con una línea por fila (servidores antiguos). */
    private static void lineas(JSONArray filas, String titulo, String texto) {
        if (texto == null || texto.trim().isEmpty()) return;
        try {
            filas.put(new JSONObject().put("k", "tit").put("t", titulo));
            int i = 0;
            for (String x : texto.split("\n")) {
                if (x.trim().isEmpty()) continue;
                filas.put(new JSONObject().put("k", "fila").put("t", x.trim()).put("s", "").put("c", "").put("alt", i++ % 2 == 0));
            }
        } catch (Exception ignored) {}
    }
}
