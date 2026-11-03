# Sri Raja Rajeshwari Finance – Developer Start Guide

Quick facts used throughout this guide:

| Item | Value |
|---|---|
| Java | 8 (Liberica JDK 8 Full: `C:\Program Files\BellSoft\LibericaJDK-8-Full`) |
| Build tool | Maven 3.9.x (`%USERPROFILE%\tools\apache-maven-3.9.9`) |
| Framework | Spring Boot 2.7.18 |
| Main class | `com.rajarajeshwari.finance.FinanceApplication` |
| Web app | http://127.0.0.1:8080 (login: `admin` / `admin123` on a brand-new database) |
| Database | PostgreSQL 15 **bundled inside the app**, port **54329**, database `postgres`, user `postgres` |
| Data folder (dev) | `<project>\data` (`pgdata`, `photos`, `backups`) |

---

## 1. Add the database connection in DBeaver

The database is an embedded PostgreSQL that only runs **while the application is running**. Start the app first (section 4, or double-click `Finance.vbs` / `start.bat`), then connect.

1. Open **DBeaver** → menu **Database → New Database Connection** (or the plug icon).
2. Choose **PostgreSQL** → **Next**. (If DBeaver offers to download the driver, click **Download**.)
3. On the **Main** tab enter:

   | Field | Value |
   |---|---|
   | Connect by | Host |
   | Host | `127.0.0.1` |
   | Port | `54329` |
   | Database | `postgres` |
   | Authentication | Database Native |
   | Username | `postgres` |
   | Password | *(leave empty – local connections are trusted)* |

4. Click **Test Connection**. It must say *Connected*. If it fails, the app is not running (see Troubleshooting below).
5. Click **Finish**. Tables are under **postgres → Databases → postgres → Schemas → public → Tables** (e.g. `debtors`, `users`, `roles`, `note_types`).

Notes
- The packaged (customer) build keeps its data in `%LOCALAPPDATA%\SriRajaRajeshwariFinance\data` but uses the same port 54329.
- Only one copy of the app can use the port at a time. A second launch re-uses the database that is already running.
- Be careful with edits: the app is live against this database. Take a backup first (**Administration > Backup & Restore** in the app).

---

## 2. Build the application with Maven

Prerequisites: JDK 8 and Maven installed (first build needs internet to download libraries; later builds can run offline with `-o`).

Open PowerShell in the project folder (`D:\koushik\FinanceProject`):

```powershell
$env:JAVA_HOME = "C:\Program Files\BellSoft\LibericaJDK-8-Full"
& "$env:USERPROFILE\tools\apache-maven-3.9.9\bin\mvn.cmd" -DskipTests clean package
```

(`mvn` can be used directly if it is on your PATH.)

Result: `target\finance-app-1.0.0.jar` (about 100+ MB runnable jar; a tiny jar of a few hundred KB means the build was interrupted – rebuild).

Run the built jar:

```powershell
java -jar target\finance-app-1.0.0.jar
```

Useful variations:

| Goal | Command |
|---|---|
| Compile only (fast check) | `mvn -o -q -DskipTests compile` |
| Offline build | add `-o` |
| Customer package (jar + Java + ready database → `dist\FinanceApp.zip`) | `powershell -ExecutionPolicy Bypass -File package.ps1` |

Note: `clean` fails if the app is still running from `target\` (the jar is locked). Exit the app from its tray icon first.

---

## 3. Import the code into Eclipse (workspace)

1. Install **Eclipse IDE for Enterprise Java** (or any Eclipse with the *m2e* Maven plugin) and make sure a **JDK 8** is registered:
   **Window → Preferences → Java → Installed JREs → Add… → Standard VM** → point to `C:\Program Files\BellSoft\LibericaJDK-8-Full` → tick it as default.
2. Create/choose a workspace (e.g. `D:\koushik\workspace`).
3. **File → Import… → Maven → Existing Maven Projects → Next**.
4. **Root Directory:** `D:\koushik\FinanceProject` – the `pom.xml` is detected and ticked → **Finish**.
5. Wait for the workspace build (progress bar bottom-right) while Maven downloads dependencies.
6. If you see errors:
   - Right-click project → **Maven → Update Project…** → tick **Force Update of Snapshots/Releases** → OK.
   - Right-click project → **Properties → Java Compiler** → compliance level **1.8**.
7. To build from Eclipse: right-click project → **Run As → Maven build…** → Goals: `-DskipTests clean package` → **Run**.

(IntelliJ IDEA: **File → Open** → select `pom.xml` → *Open as Project*; the steps in section 4 are equivalent.)

---

## 4. Start the local server from the workspace (run / debug)

### Create the launch configuration (once)
1. In Eclipse, right-click the project → **Run As → Run Configurations…** (or **Debug As → Debug Configurations…**).
2. Right-click **Java Application → New Configuration**.
3. **Main** tab:
   - Project: `finance-app`
   - Main class: `com.rajarajeshwari.finance.FinanceApplication`
4. **Arguments** tab → *Program arguments*:
   ```
   --finance.open-window=false
   ```
   (Stops the app from opening an Edge/Chrome window and tray icon on every debug run. Remove it if you want the window.)
   Optional *VM arguments*: `-Xmx512m`
5. **Environment/Working directory:** keep *Default* (the project folder) so `./data` is `D:\koushik\FinanceProject\data`.
6. **JRE** tab → Java 8.
7. Click **Apply**.

### Start and debug
1. Make sure no other copy of the app is running (exit it via the tray icon, or Task Manager → end `java.exe` / `javaw.exe` of this app).
2. Set breakpoints (double-click the left margin), e.g. in `DebtorController.java`.
3. Click **Debug** (bug icon) on the configuration. Wait for the console to show:
   ```
   Started FinanceApplication in … seconds
   ```
   Startup takes ~5–15 s once the database exists (longer the very first time).
4. Open http://127.0.0.1:8080 in a browser and log in. Breakpoints hit when you use the page.
5. Stop with the red **Terminate** square in the Console (this also stops the embedded database cleanly).

Hot reload: Java edits are applied automatically for method-body changes while debugging; for anything bigger, stop and start again. HTML/JS/CSS under `src\main\resources\static` are served from the build output – after editing, **Project → Build** (or restart) and refresh the browser with Ctrl+F5.

### Without an IDE (command line debug)
```powershell
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar target\finance-app-1.0.0.jar --finance.open-window=false
```
Then in Eclipse: **Debug Configurations → Remote Java Application** → Host `localhost`, Port `5005` → Debug.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| DBeaver "Connection refused" | The app is not running or is still starting. Start it and wait for "Started FinanceApplication". |
| "Port 8080 already in use" / second window opens | Another copy is running. Exit it from the tray icon. |
| Build error "Failed to delete target\finance-app-1.0.0.jar" | The app is running from `target\`. Exit it, then rebuild. |
| App does not open / opens slowly | Check the log: `%LOCALAPPDATA%\SriRajaRajeshwariFinance\app.log` (packaged build). Look for the `STARTUP TIMING` lines. Antivirus exclusions for the app and data folders help on slow machines. |
| Tiny jar (a few hundred KB) | Interrupted build – run `mvn -DskipTests clean package` again. |
| Eclipse shows red errors on first import | Maven → Update Project (force update); check JDK 8 is selected. |
| Forgot the admin password | Only on a brand-new database it is `admin` / `admin123`; otherwise reset it in the `users` table via DBeaver (password is a BCrypt hash) or restore a backup. |
