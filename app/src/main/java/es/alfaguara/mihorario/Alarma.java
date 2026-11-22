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
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 1.9: alarmas de las tareas. En la app web, una tarea con fecha y hora puede llevar marcada «⏰ Alarma en el móvil».
 * El servidor manda esas tareas (campo «alarmas» del JSON del widget: id, t, f, h) y aquí se programa una alarma exacta para
 * cada una. Al llegar la hora: notificación, vibración y los pitidos elegidos en Mi cuenta (6.18; 5 por defecto); se puede posponer 2 minutos o finalizar.
 * 6.18: en Mi cuenta → Configuración de avisos se pueden quitar los avisos de tareas (avisos_cfg.tareas = false): se cancelan todas.
 * Si la tarea deja de tener alarma (o se le quita la fecha o la hora, o se termina), su alarma se cancela al actualizar.
 */
public class Alarma extends BroadcastReceiver {
    static final String ACT_AVISO = "es.alfaguara.mihorario.ALARMA";
    static final String ACT_POSPONER = "es.alfaguara.mihorario.ALARMA_POSPONER";
    static final String ACT_FIN = "es.alfaguara.mihorario.ALARMA_FIN";
    static final String CANAL = "tareas";
    static final int POSPONER_MIN = 2, PITIDOS = 5;

    /** ¿Hay alarmas programadas? (para seguir actualizando los datos aunque no haya widget). */
    static boolean hay(Context c) {
        String p = Datos.prefs(c).getString("alarmas_prog", "[]");
        return p.length() > 2;
    }

    private static int codigo(String clave) { return 3000 + Math.abs(clave.hashCode() % 20000); }

    private static PendingIntent pi(Context c, String accion, String clave, String titulo, String cuando, int cod) {
        Intent i = new Intent(c, Alarma.class).setAction(accion).putExtra("clave", clave).putExtra("titulo", titulo).putExtra("cuando", cuando);
        return PendingIntent.getBroadcast(c, cod, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Programa las alarmas con los datos del servidor (se llama cada vez que se actualizan). */
    static void programar(Context c, String json) {
        if (json == null) return;
        try {
            JSONArray L = new JSONObject(json).optJSONArray("alarmas");
            if (L == null) return;                       // servidor antiguo: no se toca nada
            JSONObject cf = new JSONObject(json).optJSONObject("avisos_cfg");
            if (cf != null && !cf.optBoolean("tareas", true)) L = new JSONArray();   // 6.18: avisos de tareas desactivados
            SharedPreferences sp = Datos.prefs(c);
            Set<String> ahora = new HashSet<>();
            JSONArray prog = new JSONArray();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            long hoy = System.currentTimeMillis();
            for (int k = 0; k < L.length(); k++) {
                JSONObject a = L.optJSONObject(k);
                if (a == null) continue;
                String f = a.optString("f"), h = a.optString("h"), t = a.optString("t");
                String clave = a.optString("id") + "|" + f + "|" + h;
                ahora.add(clave);
                long cuando;
                try { cuando = fmt.parse(f + " " + h).getTime(); } catch (Exception e) { continue; }
                if (cuando <= hoy) continue;             // ya pasó (si se pospuso, esa sigue programada)
                poner(c, cuando, pi(c, ACT_AVISO, clave, t, fechaBonita(f) + " · " + h, codigo(clave)));
                prog.put(clave);
            }
            // las que ya no vienen (alarma quitada, sin fecha u hora, tarea terminada o borrada): fuera
            JSONArray antes = new JSONArray(sp.getString("alarmas_todas", "[]"));
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            for (int k = 0; k < antes.length(); k++) {
                String clave = antes.optString(k);
                if (ahora.contains(clave)) continue;
                if (am != null) am.cancel(pi(c, ACT_AVISO, clave, "", "", codigo(clave)));
            }
            sp.edit().putString("alarmas_prog", prog.toString()).putString("alarmas_todas", new JSONArray(ahora).toString()).apply();
            if (nm == null) return;
        } catch (Exception ignored) {}
    }

    private static String fechaBonita(String iso) {
        return iso.length() == 10 ? iso.substring(8, 10) + "/" + iso.substring(5, 7) : iso;
    }

    private static void poner(Context c, long t, PendingIntent p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p);
            else am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, p);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000, p);
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction(), clave = i.getStringExtra("clave"), titulo = i.getStringExtra("titulo"), cuando = i.getStringExtra("cuando");
        if (clave == null) return;
        if (titulo == null) titulo = "Tarea";
        if (cuando == null) cuando = "";
        int cod = codigo(clave);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (ACT_POSPONER.equals(a)) {
            if (nm != null) nm.cancel(cod);
            poner(c, System.currentTimeMillis() + POSPONER_MIN * 60_000L, pi(c, ACT_AVISO, clave, titulo, cuando, cod));
            return;
        }
        if (ACT_FIN.equals(a)) {
            if (nm != null) nm.cancel(cod);
            return;
        }
        if (ACT_AVISO.equals(a)) {
            mostrar(c, nm, clave, titulo, cuando, cod);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { sonar(c); } finally { pr.finish(); }
            }).start();
        }
    }

    private static void mostrar(Context c, NotificationManager nm, String clave, String titulo, String cuando, int cod) {
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Alarmas de tareas", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Aviso a la fecha y hora de las tareas con alarma");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
            ch.setSound(null, null);   // los pitidos los da la app
            nm.createNotificationChannel(ch);
        }
        String texto = "Es la hora de la tarea" + (cuando.isEmpty() ? "" : " (" + cuando + ")") + ".";
        // al tocarla se abre la app en Tareas
        Intent abrir = new Intent(c, MainActivity.class).putExtra("pantalla", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_fichar)
                .setContentTitle("⏰ " + titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(0xFF1F4A3A)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, cod, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_fichar), "Posponer " + POSPONER_MIN + " min",
                        pi(c, ACT_POSPONER, clave, titulo, cuando, cod + 20000)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_fichar), "Finalizar",
                        pi(c, ACT_FIN, clave, titulo, cuando, cod + 40000)).build());
        try { nm.notify(cod, b.build()); } catch (SecurityException ignored) {}   // sin permiso de notificaciones
    }

    /** Vibración y los pitidos de Mi cuenta (avisos_cfg.tpitidos, 0–10; 5 si el servidor no lo manda). */
    private static void sonar(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 300, 600, 300, 600}, -1));
        } catch (Exception ignored) {}
        ToneGenerator tg = null;
        try {
            tg = new ToneGenerator(AudioManager.STREAM_ALARM, 95);
            int n = Math.max(0, Math.min(10, Agenda.cfg(c).optInt("tpitidos", PITIDOS)));
            for (int k = 0; k < n; k++) {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 350);
                Thread.sleep(700);
            }
        } catch (Exception ignored) {
        } finally {
            if (tg != null) tg.release();
        }
    }
}
