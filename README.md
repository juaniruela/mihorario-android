# Mi horario · IES Alfaguara (Android)

App pequeña con un **widget** para la pantalla de inicio y la **ventana de Horarios**. Solo consulta.

- Widget: la clase de ahora (o la próxima), la siguiente, las clases de hoy, tareas vencidas o de hoy y el coche. Botón ⟳ para actualizar; se actualiza solo cada 15–30 minutos. Al tocarlo abre la ventana.
- Ventana: la página del enlace privado (`widget.php?k=…`) con Hoy, Semana, Ahora y Consultar. «Abrir la app» abre la web en el navegador.
- Datos: `widget.php?k=…&f=json` del enlace privado (Mi cuenta → «Mi horario en el móvil»). No guarda contraseñas.

## Compilar
GitHub Actions compila el APK en cada cambio de `main` (`.github/workflows/apk.yml`) y lo publica en **Releases** como `MiHorario.apk`.
En local: Android Studio (JDK 17) → abrir la carpeta → Build → Build APK(s).

La clave `app/mihorario.jks` solo sirve para firmar esta app y que cada versión se instale encima de la anterior.
