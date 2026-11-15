package es.alfaguara.mihorario;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enlace privado del widget y descarga de los datos (solo lectura). */
public final class Datos {
    private static final String PREFS = "mihorario";
    private static final Pattern ENLACE = Pattern.compile("(https?://[^\\s?#]+?/widget\\.php)\\?[^\\s#]*?\\bk=([a-f0-9]{48})");

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
        prefs(c).edit().putString("enlace", enlace).remove("json").remove("cuando").apply();
    }

    static String ultimoJson(Context c) { return prefs(c).getString("json", null); }
    static long cuando(Context c) { return prefs(c).getLong("cuando", 0); }

    /** Descarga los datos (JSON) y los guarda para verlos también sin conexión. */
    static String descargar(Context c) throws Exception {
        String e = enlace(c);
        if (e == null) throw new IllegalStateException("Falta el enlace");
        HttpURLConnection con = (HttpURLConnection) new URL(e + "&f=json").openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setUseCaches(false);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "MiHorario-Android/1.0");
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
