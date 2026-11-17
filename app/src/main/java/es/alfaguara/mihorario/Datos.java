package es.alfaguara.mihorario;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enlace privado del widget, día elegido y descarga de los datos (solo lectura). */
public final class Datos {
    private static final String PREFS = "mihorario";
    private static final Pattern ENLACE = Pattern.compile("(https?://[^\\s?#]+?/widget\\.php)\\?[^\\s#]*?\\bk=([a-f0-9]{48})");
    /** Si nadie toca ‹ › en este tiempo, el widget vuelve solo a hoy. */
    static final long VOLVER_A_HOY_MS = 20 * 60 * 1000L;

    private Datos() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Busca un enlace de widget válido en un texto (lo que se pega o se comparte) y lo deja «limpio»: …/widget.php?k=CLAVE */
    static String limpiar(String texto) {
        if (texto == null) return null;
        Matcher m = ENLACE.matcher(texto.trim());
        return m.find() ? m.group(1) + "?k=" + m.group(2) : null;
    }

    static String enlace(Context c) { return prefs(c).getString("enlace", null); }

    static void guardarEnlace(Context c, String enlace) {
        prefs(c).edit().putString("enlace", enlace).remove("json").remove("cuando").remove("sel").apply();
    }

    static String ultimoJson(Context c) { return prefs(c).getString("json", null); }
    static long cuando(Context c) { return prefs(c).getLong("cuando", 0); }

    /* ---------- fechas ---------- */
    static String iso(Date d) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d); }
    static String hoy() { return iso(new Date()); }
    static String sumar(String iso, int n) {
        try {
            Calendar k = Calendar.getInstance();
            k.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso));
            k.add(Calendar.DAY_OF_MONTH, n);
            return iso(k.getTime());
        } catch (Exception e) { return hoy(); }
    }

    /** Día que enseña el widget (por defecto hoy). */
    static String sel(Context c) {
        String s = prefs(c).getString("sel", null);
        return s == null ? hoy() : s;
    }
    static void elegir(Context c, String iso) {
        prefs(c).edit().putString("sel", iso).putLong("sel_t", System.currentTimeMillis()).apply();
    }
    /** En las actualizaciones automáticas: si hace rato que no se cambia de día (o ya es otro día), vuelve a hoy. */
    static void volverAHoySiToca(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("sel")) return;
        if (System.currentTimeMillis() - p.getLong("sel_t", 0) > VOLVER_A_HOY_MS) p.edit().remove("sel").remove("sel_t").apply();
    }

    /** 1.11: descarga los datos de un día sin tocar el día elegido en el widget ni lo guardado (para la agenda). */
    static String bajar(Context c, String dia) throws Exception {
        String e = enlace(c);
        if (e == null) throw new IllegalStateException("Falta el enlace");
        HttpURLConnection con = (HttpURLConnection) new URL(e + "&f=json&d=" + dia).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setUseCaches(false);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MiHorario-Android/1.11");
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String txt = leer(in);
        con.disconnect();
        if (code >= 400 || !txt.trim().startsWith("{")) throw new IllegalStateException("Sin datos (" + code + ")");
        return txt;
    }

    /** Descarga los datos (JSON) del día elegido y los guarda para verlos también sin conexión. */
    static String descargar(Context c) throws Exception {
        String e = enlace(c);
        if (e == null) throw new IllegalStateException("Falta el enlace");
        String dia = sel(c);
        HttpURLConnection con = (HttpURLConnection) new URL(e + "&f=json&d=" + dia).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setUseCaches(false);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MiHorario-Android/1.1");
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String txt = leer(in);
        con.disconnect();
        if (code == 403) throw new IllegalStateException("Enlace no válido o anulado");
        if (code >= 400) throw new IllegalStateException("Error del servidor (" + code + ")");
        if (!txt.trim().startsWith("{")) throw new IllegalStateException("Respuesta no válida");
        prefs(c).edit().putString("json", txt).putLong("cuando", System.currentTimeMillis()).apply();
        return txt;
    }

    private static String leer(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
