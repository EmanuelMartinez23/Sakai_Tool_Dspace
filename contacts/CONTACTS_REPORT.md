### Contactos — Nueva herramienta para Sakai (ID: `sakai.contacts`)

#### Resumen
Se añadió una herramienta nueva llamada `Contactos` registrada como `sakai.contacts`, disponible para añadir en Sitios (categorías `course` y `project`). La herramienta sigue la estructura modular estándar (api/impl/tool), usa Spring MVC + Thymeleaf en el módulo `tool` y un servicio in‑memory en `impl` (pensado como base para evolucionar a persistencia real).

- UI: Spring MVC + Thymeleaf
- Permisos: crear contactos reservado a rol con permiso de mantenimiento del sitio (maintain). Lectura para todos los participantes.
- Campos del contacto: nombre completo, email, teléfono, rol, etiquetas, notas (mínimos en esta versión inicial: nombre, email, teléfono, rol, etiquetas).
- Módulos creados: `contacts/api`, `contacts/impl`, `contacts/tool`.

Estructura base inspirada en herramientas modernas del repositorio (p.ej. `rubrics`, `dashboard`, etc.) para el arranque sin `web.xml` usando `WebApplicationInitializer`.

---

#### Estructura del proyecto

- `contacts/pom.xml`: POM agregador del módulo Contactos.
- `contacts/api` (JAR `contacts-api`)
  - `org.sakaiproject.contacts.api.Contact`
  - `org.sakaiproject.contacts.api.ContactService`
- `contacts/impl` (componente `sakai-component` `contacts-impl`)
  - `org.sakaiproject.contacts.impl.ContactServiceImpl` (implementación en memoria)
  - `src/webapp/WEB-INF/components.xml` registrando el bean `org.sakaiproject.contacts.api.ContactService`
- `contacts/tool` (WAR `contacts-tool`)
  - Bootstrap: `org.sakaiproject.contacts.tool.ContactsConfiguration` (WebApplicationInitializer)
  - Config MVC: `org.sakaiproject.contacts.tool.ContactsWebMvcConfiguration`
  - Controlador: `org.sakaiproject.contacts.tool.ContactsController`
  - Vistas: `/WEB-INF/templates/index.html` (Thymeleaf)
  - Registro de herramienta: `/WEB-INF/tools/sakai.contacts.xml`

Además, se añadieron las claves i18n del título y descripción:
- `config/localization/bundles/src/bundle/org/sakaiproject/localization/bundle/tool/tools.properties`
  - `sakai.contacts.title = Contacts`
  - `sakai.contacts.description = Contacts DS`
- `config/localization/bundles/src/bundle/org/sakaiproject/localization/bundle/tool/tools_es.properties`
  - `sakai.contacts.title = Contactos`
  - `sakai.contacts.description = Contacts DS`

El módulo `contacts` fue agregado a los perfiles de compilación del `pom.xml` raíz para incluirlo en el build.

---

#### Cómo compilar

Requisitos previos (de acuerdo al README del repo):
- Java 8
- Maven o el Wrapper (`./mvnw`)

Compilar todo Sakai (incluyendo Contactos):
```
mvn clean install -DskipTests
```

Si prefieres usar el wrapper:
```
./mvnw clean install -DskipTests
```

Nota: la primera compilación puede tardar varios minutos por la descarga de dependencias.

---

#### Cómo desplegar en Tomcat 9

1) Configurar Tomcat 9 según la guía oficial de Sakai:
https://confluence.sakaiproject.org/display/BOOT/Install+Tomcat+9

2) Realizar el despliegue usando el plugin `sakai:deploy` indicando `CATALINA_HOME`:
```
mvn clean install sakai:deploy -Dmaven.tomcat.home=/ruta/a/tu/tomcat
```

3) Iniciar Tomcat y revisar logs:
```
cd /ruta/a/tu/tomcat/bin
./startup.sh && tail -f ../logs/catalina.out
```

4) Acceder al portal:
- URL: http://localhost:8080/portal

---

#### Cómo añadir la herramienta en un Sitio

1) Entra al sitio (curso o proyecto) donde la quieras añadir.
2) Ve a "Site Info" (o "Información del sitio").
3) Elige "Manage Tools" (o "Gestionar herramientas").
4) Busca la herramienta con el título "Contactos" (o "Contacts" según idioma) y selecciónala.
5) Guarda los cambios. La herramienta aparecerá en la navegación del sitio.

---

#### Funcionamiento actual (v1 mínima)

- Listado de contactos del sitio.
- Alta de contacto (solo para rol con permisos de mantenimiento del sitio — típicamente Instructor/Teacher).
- Visibilidad de la lista para todos los participantes del sitio.

Pendientes para paridad completa con "Encuestas"/Polls (siguientes iteraciones):
- Edición y borrado de contactos.
- Búsqueda/filtrado y ordenación.
- Exportación (CSV/Excel).
- Endpoints REST/EntityBroker.
- Persistencia en base de datos y/o integración con participantes del sitio.
- Internacionalización completa de la interfaz (textos de la vista ya están en español, pero conviene mover a bundles).
- Iconografía e integración visual adicional (opcional).

---

#### Notas técnicas

- Patrón de arranque sin `web.xml` mediante `WebApplicationInitializer` (como `rubrics`), con filtros/listeners Sakai (`ToolListener`, `SakaiContextLoaderListener`, `RequestFilter`).
- `ContactServiceImpl` es in‑memory para simplificar el arranque; se recomienda migrarlo a una implementación con almacenamiento persistente (por ejemplo, usando Hibernate + kernel storage util) en una fase posterior.
- El permiso para crear contactos se valida con `SecurityService` comparando contra `SiteService.SECURE_UPDATE_SITE` en la referencia del sitio.

---

#### Troubleshooting

- Si no aparece la herramienta al "Gestionar herramientas":
  - Verifica que el WAR `contacts-tool` se copió en `tomcat/webapps/` y que el registro `/WEB-INF/tools/sakai.contacts.xml` está dentro del WAR.
  - Asegúrate de haber compilado y desplegado con `sakai:deploy` tras añadir el módulo al `pom.xml` raíz.
  - Revisa que existan las claves `sakai.contacts.title` y `.description` en los bundles de `config/localization`.
- Si la página muestra error 500 al entrar a la herramienta:
  - Revisa `catalina.out` y confirma que no hay conflictos de beans Spring ni dependencias faltantes.
  - Confirma que tienes sesión válida en Sakai (el controlador requiere sesión de usuario).

---

#### Créditos

Implementación inicial preparada para iterar hacia paridad funcional con "Encuestas" (Polls), manteniendo una base moderna con Spring MVC + Thymeleaf.
