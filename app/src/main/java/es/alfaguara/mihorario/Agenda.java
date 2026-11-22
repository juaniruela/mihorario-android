package es.alfaguara.mihorario;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 6.19: son dos avisos: «🚙 Turno de coche» (solo a quien tiene turno ese día, solo con los datos del turno) y
 * «📅 Mi horario de hoy» (para todos: horario, tareas y el resto del día). Solo días laborables con clase; a mano, los dos.
 * 1.11: agenda de cada mañana laborable. A la hora elegida en Mi cuenta → Configuración de avisos (07:00 por defecto)
 * llega una notificación con la agenda del día: primero los turnos, luego mi horario, las tareas del día y el resto de bloques.
 * Vibración y pitidos según la configuración. También se puede enviar a mano («⋯ Más» → «Enviar ahora la agenda»).
 * La misma configuración trae si se quiere el aviso para fichar en Séneca.
 */
public class Agenda extends BroadcastReceiver {
    static final String ACT_AUTO = "es.alfaguara.mihorario.AGENDA";
    static final String ACT_MANUAL = "es.alfaguara.mihorario.AGENDA_MANUAL";
    static final String CANAL = "agenda";
    static final int CODIGO = 60, NOTIF = 80, NOTIF_TURNO = 81;   // 6.19: dos avisos

    static JSONObject cfg(Context c) {
        try { return new JSONObject(Datos.prefs(c).getString("avisos_cfg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static boolean activa(Context c) { return cfg(c).optBoolean("agenda", true) && Datos.enlace(c) != null; }

    private static PendingIntent pi(Context c, String accion) {
        return PendingIntent.getBroadcast(c, CODIGO, new Intent(c, Agenda.class).setAction(accion),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Guarda la configuración que manda el servidor y programa la próxima agenda. */
    static void programar(Context c, String json) {
        try {
            if (json != null) {
                JSONObject o = new JSONObject(json).optJSONObject("avisos_cfg");
                if (o != null) {
                    SharedPreferences sp = Datos.prefs(c);
                    boolean fichar = o.optBoolean("fichar", true);
                    sp.edit().putString("avisos_cfg", o.toString()).apply();
                    if (fichar != Fichar.activo(c)) Fichar.activar(c, fichar);   // el aviso de fichar se elige en Mi cuenta
                }
            }
        } catch (Exception ignored) {}
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(c, ACT_AUTO);
        if (!activa(c)) { am.cancel(p); return; }
        long t = proxima(cfg(c).optString("hora", "07:00"));
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p);
            else am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, p);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, p);
        }
    }

    /** Próximo día laborable (lunes a viernes) a esa hora. */
    private static long proxima(String hhmm) {
        int h = 7, m = 0;
        try { String[] x = hhmm.split(":"); h = Integer.parseInt(x[0]); m = Integer.parseInt(x[1]); } catch (Exception ignored) {}
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, h); k.set(Calendar.MINUTE, m); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 5_000) k.add(Calendar.DAY_OF_MONTH, 1);
        while (k.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || k.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    /** «⋯ Más» → «Enviar ahora la agenda». */
    static void enviarAhora(Context c) {
        c.sendBroadcast(new Intent(c, Agenda.class).setAction(ACT_MANUAL));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        final String a = i.getAction();
        if (!ACT_AUTO.equals(a) && !ACT_MANUAL.equals(a)) return;
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                boolean manual = ACT_MANUAL.equals(a);
                String json = null;
                try { json = Datos.bajar(app, Datos.hoy()); } catch (Exception e) { json = manual ? Datos.ultimoJson(app) : null; }
                if (json != null) {
                    JSONObject sel = new JSONObject(json).optJSONObject("sel");
                    // 6.19: la automática solo los días laborables con clase (ni fines de semana, ni festivos, ni días sin clase);
                    // la manual («Enviar ahora la agenda») manda siempre los dos avisos
                    if (manual || sel == null || !sel.optBoolean("libre", false)) mostrar(app, json, manual);
                } else if (manual) mostrarTexto(app, "📅 Mi horario de hoy", "No se ha podido descargar la agenda. Comprueba la conexión.", NOTIF, true);
            } catch (Exception ignored) {
            } finally {
                if (ACT_AUTO.equals(a)) programar(app, null);   // la del siguiente día laborable
                pr.finish();
            }
        }).start();
    }

    /** 6.19: dos avisos. 1) «🚙 Turno de coche»: solo los datos del turno de ese día, y solo a quien tiene turno (la manual
     *  lo manda siempre: si no hay turno, lo dice). 2) «📅 Mi horario de hoy»: el horario del día, las tareas y el resto de
     *  bloques del día (sin turnos ni las listas del profesorado), para todo el profesorado. Suenan una sola vez. */
    private static void mostrar(Context c, String json, boolean manual) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject sel = o.optJSONObject("sel");
        JSONArray B = sel != null ? sel.optJSONArray("bloques") : null;
        List<JSONObject> turnos = new ArrayList<>(), horario = new ArrayList<>(), tareas = new ArrayList<>(), resto = new ArrayList<>();
        if (B != null) for (int k = 0; k < B.length(); k++) {
            JSONObject b = B.optJSONObject(k);
            if (b == null) continue;
            String t = b.optString("tit");
            if (t.startsWith("De guardia") || t.startsWith("Función directiva") || t.contains("Profesorado") || t.startsWith("Ahora")) continue;
            if (t.startsWith("Turno")) turnos.add(b);
            else if (t.startsWith("Horario")) horario.add(b);
            else if (t.startsWith("Tarea")) tareas.add(b);
            else resto.add(b);
        }
        String dia = sel != null ? sel.optString("dia", "") : "";
        String sufijo = dia.isEmpty() ? "" : " · " + dia;
        // 1) turno de coche: solo las filas de turno de verdad (las que traen los datos de Tribbu «tb»)
        StringBuilder tu = new StringBuilder();
        for (JSONObject b : turnos) {
            JSONArray F = b.optJSONArray("filas");
            if (F != null) for (int k = 0; k < F.length(); k++) {
                JSONObject f = F.optJSONObject(k);
                if (f == null || f.optJSONObject("tb") == null) continue;
                String t = f.optString("t").trim(), s = f.optString("s").trim();
                tu.append(tu.length() > 0 ? "\n" : "").append("• ").append(t).append("\n");
                if (!s.isEmpty()) for (String l : s.split("\n")) if (!l.trim().isEmpty()) tu.append("   ").append(l.trim()).append("\n");
            }
        }
        boolean hayTurno = tu.length() > 0;
        // 2) horario del día (y tareas y demás bloques del día)
        StringBuilder sb = new StringBuilder();
        List<JSONObject> orden = new ArrayList<>();
        orden.addAll(horario); orden.addAll(tareas); orden.addAll(resto);
        for (JSONObject b : orden) {
            JSONArray F = b.optJSONArray("filas");
            if (F == null || F.length() == 0) continue;
            String tit = b.optString("tit");
            if (tit.equals("Horario")) tit = "Mi horario";
            sb.append(sb.length() > 0 ? "\n" : "").append("▸ ").append(tit.toUpperCase()).append("\n");
            for (int k = 0; k < F.length() && k < 25; k++) {
                JSONObject f = F.optJSONObject(k);
                if (f == null) continue;
                String t = f.optString("t").trim(), s = f.optString("s").trim();
                sb.append("• ").append(t);
                if (!s.isEmpty()) sb.append(" — ").append(s.replace("\n", " · "));
                sb.append("\n");
            }
        }
        if (hayTurno || manual)
            mostrarTexto(c, "🚙 Turno de coche" + sufijo, hayTurno ? tu.toString().trim() : "Hoy no tienes turno de coche.", NOTIF_TURNO, false);
        else { NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); if (nm != null) nm.cancel(NOTIF_TURNO); }
        mostrarTexto(c, "📅 Mi horario de hoy" + sufijo, sb.length() == 0 ? "Nada para hoy." : sb.toString().trim(), NOTIF, true);
    }

    private static void mostrarTexto(Context c, String titulo, String texto, int id, boolean sonar) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        JSONObject cf = cfg(c);
        boolean vibrar = cf.optBoolean("vibrar", true);
        int pitidos = Math.max(0, Math.min(10, cf.optInt("pitidos", 5)));
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Agenda de la mañana", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tu agenda del día: turnos, horario, tareas…");
            ch.enableVibration(false);   // la vibración y los pitidos los da la app, según Mi cuenta
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        String primera = texto.split("\n", 3).length > 1 ? texto.split("\n", 3)[1].replace("• ", "") : texto;
        Intent abrir = new Intent(c, MainActivity.class).putExtra("pantalla", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_fichar)
                .setContentTitle(titulo)
                .setContentText(primera)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(0xFF1F4A3A)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 61 + (id - NOTIF), abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) {}
        if (!sonar) return;   // 6.19: con dos avisos, vibra y pita solo una vez
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrar && v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 250, 500, 250, 500}, -1));
        } catch (Exception ignored) {}
        if (pitidos > 0) {
            ToneGenerator tg = null;
            try {
                tg = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
                for (int k = 0; k < pitidos; k++) { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 300); Thread.sleep(650); }
            } catch (Exception ignored) {
            } finally { if (tg != null) tg.release(); }
        }
    }
}
