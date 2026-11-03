# Sri Raja Rajeshwari Finance

Offline desktop finance application for a daily-collection business (debtors, deposits/advances, daily
payments, account closing and reports). Converted from an ASP.NET WebForms + SQL Server application.

**Tech stack:** Java 8 Â· Spring Boot 2.7 Â· PostgreSQL 15 (bundled inside the app - nothing to install) Â·
plain HTML/CSS/JavaScript UI Â· Maven.

It runs completely offline. The app opens in its own window (Microsoft Edge or Google Chrome "app mode",
otherwise your default browser) and shows an icon in the system tray.

---

## Contents
1. [Option A - Run the ready-made package (easiest, no Java needed)](#option-a---run-the-ready-made-package)
2. [Option B - Build and run from source code](#option-b---build-and-run-from-source)
3. [First login](#first-login)
4. [Moving your data to another PC](#moving-your-data-to-another-pc)
5. [Copying data from the old SQL Server application](#copying-data-from-the-old-sql-server-application)
6. [Where data is stored / viewing the database](#where-data-is-stored)
7. [Stopping, restarting, updating](#stopping-restarting-updating)
8. [Troubleshooting](#troubleshooting)
9. [Project layout](#project-layout)

**Requirements on the PC:** Windows 10 or 11 (64-bit), about 1 GB free disk space, and Microsoft Edge or
Chrome (Edge is already part of Windows). No internet is needed to run the app.

---

## Option A - Run the ready-made package

Use this on a PC that only needs to *use* the application.

### Step 1 - Create the package (once, on the PC where the code is built)
```powershell
powershell -ExecutionPolicy Bypass -File package.ps1
```
This produces `dist\FinanceApp.zip` (about 180 MB). It contains the application **and its own Java
runtime**, so the other PC needs nothing installed.

### Step 2 - Install on the other PC
1. Copy `FinanceApp.zip` to the other PC (pen drive, network share, ...).
2. Right-click the zip -> **Extract All...** and extract it somewhere permanent, for example `C:\FinanceApp`.
   (Do not run it from inside the zip, and avoid folders such as Program Files that need administrator rights.)
3. Open the extracted folder and double-click **`Create-Desktop-Shortcut.bat`** once. A shortcut
   *Sri Raja Rajeshwari Finance* appears on the desktop.
4. Double-click the desktop shortcut (or `Finance.bat`) to start the app.
5. The first start takes about 30 seconds. The application window then opens by itself.

Data of this installation is kept in `%LOCALAPPDATA%\SriRajaRajeshwariFinance`
(typically `C:\Users\<name>\AppData\Local\SriRajaRajeshwariFinance`), separate from the program folder.

---

## Option B - Build and run from source

Use this on a PC where the code will be developed or built.

### Step 1 - Install the prerequisites
| Tool | Version | Notes |
|---|---|---|
| **Java JDK** | **8** (1.8) | Must be Java 8. Example: *Liberica JDK 8 Full* or *Eclipse Temurin 8*. Install with `winget install BellSoft.LibericaJDK.8.Full` |
| **Apache Maven** | 3.8 or newer | Download from https://maven.apache.org/download.cgi , unzip, and add its `bin` folder to `PATH` |

Check the installation in a new Command Prompt:
```bat
java -version
mvn -version
```
`java -version` must show `1.8.0_...`. If several Java versions are installed, set `JAVA_HOME` to the
Java 8 folder, for example:
```bat
setx JAVA_HOME "C:\Program Files\BellSoft\LibericaJDK-8-Full"
```
(open a new Command Prompt afterwards).

### Step 2 - Get the code
Unzip `FinanceProject-source.zip` (or clone the git repository) to a folder such as `D:\FinanceProject`.

### Step 3 - Start
Double-click **`start.bat`** (or run it from a Command Prompt in the project folder).

* On the first run it builds the project. This **downloads libraries from the internet** (about 150 MB,
  including the bundled PostgreSQL binaries) and takes a few minutes. Later starts are quick and
  need no internet.
* `start.bat` rebuilds automatically whenever the source code has changed.
* The first start of the application takes about 30 seconds, then the window opens.

To build manually instead:
```bat
mvn -DskipTests package
java -jar target\finance-app-1.0.0.jar
```

Data is stored in the `data` folder inside the project folder.

### Optional - desktop shortcut
Create a shortcut to `Finance.vbs` (it starts the app without a console window) and, if you like, use
`finance.ico` as its icon.

---

## First login

* **New, empty database:** user **`admin`**, password **`admin123`**, role *Admin*.
  Change it straight away under **Administration -> Change Password**.
* **After restoring a backup or importing the old data:** use the user ids and passwords that were in
  that data.

---

## Moving your data to another PC

The application has built-in backup and restore. This is the way to move or copy your data.

**On the PC that has the data**
1. Log in as an administrator.
2. Open **Administration -> Backup & Restore** and click **Download backup now**.
3. A file such as `finance-backup-20261020-1849.zip` is downloaded (normally to the *Downloads* folder).
   It contains all debtors, deposits, payments, users and photos.

**On the new PC**
1. Install and start the app (Option A or B) and log in with the first-login account above.
2. Open **Administration -> Backup & Restore**.
3. Choose the backup zip, type `RESTORE` in the confirmation box and click **Restore from backup**.
4. Log in again with the user ids and passwords of the *old* PC (the users come from the backup).

Restore **replaces all data** on the new PC. Before it does, the app saves the current data as
`pre-restore-....zip` in the `backups` folder next to the database, so a mistaken restore can be undone.
A damaged or foreign zip file is rejected and nothing is changed.

*Alternative (advanced):* stop the app (tray icon -> Exit) and copy the whole `data` folder
(or `%LOCALAPPDATA%\SriRajaRajeshwariFinance`) to the same place on the other PC.

**Back up regularly** - at the end of each working day - and keep copies on another device.

---

## Copying data from the old SQL Server application

Only needed once, when moving from the old ASP.NET application. Run on the PC that has the old
SQL Server database (the database is only read, never changed):

1. Export to CSV files:
   ```powershell
   powershell -ExecutionPolicy Bypass -File migration\export-from-sqlserver.ps1
   ```
   (Defaults: server `.\SQLEXPRESS`, database `RAJARAJESHWARI`, Windows login. Use `-Server` / `-Database`
   to change them.) The files appear in `migration\data`.
2. Import them into an **empty** new database (stop the app first):
   ```bat
   java -jar target\finance-app-1.0.0.jar --import-legacy=migration/data
   ```
   Wait until it prints `IMPORTED payments: ...`, then stop it with Ctrl+C.
3. Compare the numbers with the old system (the app keeps running afterwards - stop it with Ctrl+C):
   ```bat
   java -jar target\finance-app-1.0.0.jar --stats
   ```
4. Delete the `migration\data` folder afterwards - it contains your customers' data.

Passwords are converted to secure hashes during the import; users log in with their old passwords.

---

## Where data is stored

| Item | Option A (package) | Option B (source) |
|---|---|---|
| Database files | `%LOCALAPPDATA%\SriRajaRajeshwariFinance\data\pgdata` | `<project>\data\pgdata` |
| Debtor photos | `...\SriRajaRajeshwariFinance\photos` | `<project>\data\photos` |
| Automatic safety backups | `...\SriRajaRajeshwariFinance\backups` | `<project>\data\backups` |
| PostgreSQL program files | unpacked at start into `%TEMP%\embedded-pg` (re-created automatically) | same |

**Viewing the data with a database tool** (for example DBeaver, free). While the app is running, connect to:

* Host `127.0.0.1`, port `54329`, database `postgres`, user `postgres`, **no password**.
* It accepts connections from the same PC only. Use a **read-only** connection and make changes
  through the application, not the tool.

---

## Stopping, restarting, updating

* **Stop:** right-click the tray icon (next to the clock) -> **Exit**. This shuts the database down cleanly.
  Closing only the browser window does **not** stop the app.
* **Start again:** use the desktop shortcut. If the app is already running, the shortcut just opens a window.
* **Update to a new version:** take a backup, stop the app, replace the program folder with the new version
  (keep the data folder), and start again. Database changes are applied automatically at start-up.

---

## Troubleshooting

| Problem | What to do |
|---|---|
| Nothing happens / window does not open | Wait up to a minute on the first start. Then open **http://127.0.0.1:8080** in any browser. |
| "Port 8080 already in use" or the page shows something else | Another program uses port 8080. Run the app with another port: `java -jar target\finance-app-1.0.0.jar --server.port=8090` and open `http://127.0.0.1:8090`. |
| Database will not start / "port 54329" error | An old copy is still running. Open Task Manager, end all `java.exe` and `postgres.exe` processes, and start again. (The app also reuses a database left running from an earlier session.) |
| `start.bat` says the build failed | Check `java -version` shows 1.8 and `mvn -version` works. The first build needs internet access. |
| "Run as administrator" problems | Do **not** run as administrator - PostgreSQL refuses to start with administrator rights. |
| Antivirus / Defender blocks `postgres.exe` | Allow the app (it is the bundled database server). |
| Forgot the password | Restore a backup that has a known user, or ask the developer to reset the password in the database. |
| Login says "locked" | Five wrong passwords lock that user for 10 minutes. Wait and try again. |
| Cannot open from another PC on the network | By design the app listens on this PC only (`127.0.0.1`). |
| PDF / Excel download is blocked | Allow downloads for the app window in Edge/Chrome. |

Logs: when started with `start.bat`, messages appear in its console window.

---

## Project layout

```
pom.xml                         Maven build (Java 8, Spring Boot)
start.bat / Finance.vbs         start from source (console / without console window)
package.ps1                     builds dist\FinanceApp.zip (app + bundled Java runtime)
migration\                      export script for the old SQL Server database
docs\legacy\                    original SQL Server tables and stored procedures (reference)
src\main\java\...               REST API: debtors, deposits, payments, reports, export, backup/restore, login
src\main\resources\db\migration PostgreSQL schema (applied automatically by Flyway)
src\main\resources\static       the user interface (HTML/CSS/JS, works offline, responsive for phones)
```

Useful command-line options of the jar:

| Option | Meaning |
|---|---|
| `--server.port=8090` | change the web port (default 8080) |
| `--finance.data-dir=D:\fin\data` | where the database is kept |
| `--finance.photo-dir=...` / `--finance.backup-dir=...` | photos / safety-backup folders |
| `--finance.open-window=false` | do not open a window or tray icon (server only) |
| `--import-legacy=<folder>` / `--stats` | one-off import of the old data / print row counts |

