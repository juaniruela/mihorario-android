package es.alfaguara.mihorario;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Aviso para fichar en Séneca: 5 minutos antes de la primera actividad del día (entrada) y al acabar la última (salida).
 * La notificación hace vibrar el móvil y da dos pitidos dobles; se puede posponer 2 minutos o cerrar.
 * Las horas vienen del servidor (campo «fichar» del JSON del widget): no hay aviso en fin de semana, festivos ni de baja.
 */
public class Fichar extends BroadcastReceiver {
    static final String ACT_AVISO = "es.alfaguara.mihorario.FICHAR";
    static final String ACT_POSPONER = "es.alfaguara.mihorario.FICHAR_POSPONER";
    static final String ACT_CERRAR = "es.alfaguara.mihorario.FICHAR_CERRAR";
    static final String CANAL = "fichar";
    static final String URL_SENECA = "https://seneca.juntadeandalucia.es/seneca/";
    static final int ANTES_MIN = 5, POSPONER_MIN = 2;

    /* ---------- activar / desactivar (menú «Más») ---------- */
    static boolean activo(Context c) { return Datos.prefs(c).getBoolean("fichar_on", true); }
    static void activar(Context c, boolean on) {
        Datos.prefs(c).edit().putBoolean("fichar_on", on).apply();
        if (!on) { cancelar(c, "entrada"); cancelar(c, "salida"); }
        else programar(c, Datos.ultimoJson(c));
    }

    private static int codigo(String tipo, boolean pospuesto) { return ("salida".equals(tipo) ? 31 : 30) + (pospuesto ? 2 : 0); }
    private static int idNotif(String tipo) { return "salida".equals(tipo) ? 71 : 70; }

    private static PendingIntent pi(Context c, String accion, String tipo, String hora, int codigo) {
        Intent i = new Intent(c, Fichar.class).setAction(accion).putExtra("tipo", tipo).putExtra("hora", hora);
        return PendingIntent.getBroadcast(c, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Programa los avisos de hoy con los datos del servidor (se llama cada vez que se actualizan los datos). */
    static void programar(Context c, String json) {
        if (!activo(c) || json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject f = o.optJSONObject("fichar");
            if (f == null || !Datos.hoy().equals(f.optString("fecha"))) { cancelar(c, "entrada"); cancelar(c, "salida"); return; }
            alarma(c, "entrada", f.optString("entrada"), -ANTES_MIN);
            alarma(c, "salida", f.optString("salida"), 0);
        } catch (Exception ignored) {}
    }

    private static void alarma(Context c, String tipo, String hora, int desfase) {
        String[] p = hora == null ? new String[0] : hora.split(":");
        if (p.length < 2) return;
        Calendar k = Calendar.getInstance();
        try {
            k.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
            k.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
        } catch (Exception e) { return; }
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        k.add(Calendar.MINUTE, desfase);
        // ya pasó: nada (el aviso pospuesto va aparte y no se toca)
        if (k.getTimeInMillis() <= System.currentTimeMillis()) return;
        poner(c, k.getTimeInMillis(), pi(c, ACT_AVISO, tipo, hora, codigo(tipo, false)));
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

    private static void cancelar(Context c, String tipo) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c, ACT_AVISO, tipo, "", codigo(tipo, false)));
        am.cancel(pi(c, ACT_AVISO, tipo, "", codigo(tipo, true)));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction(), tipo = i.getStringExtra("tipo"), hora = i.getStringExtra("hora");
        if (tipo == null) tipo = "entrada";
        if (hora == null) hora = "";
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (ACT_POSPONER.equals(a)) {
            if (nm != null) nm.cancel(idNotif(tipo));
            poner(c, System.currentTimeMillis() + POSPONER_MIN * 60_000L, pi(c, ACT_AVISO, tipo, hora, codigo(tipo, true)));
            return;
        }
        if (ACT_CERRAR.equals(a)) {
            if (nm != null) nm.cancel(idNotif(tipo));
            return;
        }
        if (ACT_AVISO.equals(a) && activo(c)) {
            mostrar(c, nm, tipo, hora);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { sonar(c); } finally { pr.finish(); }
            }).start();
        }
    }

    private static void mostrar(Context c, NotificationManager nm, String tipo, String hora) {
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Fichar en Séneca", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Aviso para fichar al empezar y al acabar la jornada");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 500, 250, 500});
            ch.setSound(null, null);   // los pitidos los da la app
            nm.createNotificationChannel(ch);
        }
        boolean entrada = !"salida".equals(tipo);
        String titulo = entrada ? "⏰ Ficha la entrada en Séneca" : "⏰ Ficha la salida en Séneca";
        String texto = entrada ? "Empiezas a las " + hora + ". Recuerda fichar." : "Has terminado la jornada (" + hora + "). Recuerda fichar la salida.";
        int id = idNotif(tipo);
        Notification.Builder b = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_fichar)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(0xFF1F4A3A)
                .setAutoCancel(true)
                // al tocarla se abre Séneca (directamente: Android 12+ no deja abrir apps desde un receptor)
                .setContentIntent(PendingIntent.getActivity(c, 40 + id,
                        new Intent(Intent.ACTION_VIEW, Uri.parse(URL_SENECA)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_fichar), "Posponer " + POSPONER_MIN + " min",
                        pi(c, ACT_POSPONER, tipo, hora, 50 + id)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_fichar), "Cerrar",
                        pi(c, ACT_CERRAR, tipo, hora, 60 + id)).build());
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) {}   // sin permiso de notificaciones
    }

    /** Vibración y dos pitidos dobles. */
    private static void sonar(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 250, 500}, -1));
        } catch (Exception ignored) {}
        ToneGenerator tg = null;
        try {
            tg = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 500);
            Thread.sleep(900);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 500);
            Thread.sleep(800);
        } catch (Exception ignored) {
        } finally {
            if (tg != null) tg.release();
        }
    }
}
