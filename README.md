# Personal Expense Tracker

A full-stack Java web app for tracking income and spending. A Java backend serves a REST API and a browser frontend, and it runs with **no external libraries**: only the JDK.

**Built by Geetansh** | Java 17+ | REST API | HTML/CSS/JavaScript frontend

## What it does

- Add, edit and delete income and expense transactions
- Browse month by month with previous/next controls
- Set a monthly budget and see spending against it in one stacked bar, split by category
- See income, total spent and what is left after spending
- Filter by type (All / Spent / Income) and search by title, category or note
- Export any month to CSV (safe to open in Excel)
- Data survives restarts (stored in `data/expenses.csv`)

## Run it

You need a **JDK 17 or newer** (`java -version` and `javac -version` should both work).

| System | Command |
|---|---|
| Windows | `run.bat --demo` |
| macOS / Linux | `./run.sh --demo` |

Then open **http://localhost:8080**.

`--demo` loads sample data the first time. Leave it off to start empty.
Other options: `--port 9090` to change the port, `--data mydata` to change the data folder.

**Run the tests:** `test.bat` (Windows) or `./test.sh` (macOS/Linux).

**Using an IDE?** Open the folder in IntelliJ IDEA, Eclipse or VS Code (it imports through `pom.xml`), then run `com.geetansh.expensetracker.Main`. The web files in `src/main/resources/static` are picked up automatically.

## How it is built

```
Browser (index.html, style.css, app.js)
        |  JSON over HTTP
        v
web/ApiServer        routing, JSON in/out, security checks
        v
service/TransactionService     validation and business rules, monthly totals, CSV export
        v
repository/TransactionRepository  (interface)
        v
repository/CsvTransactionRepository   in-memory map + atomic CSV file writes
```

```
src/main/java/com/geetansh/expensetracker/
  Main.java                 starts everything, reads --port / --data / --demo
  model/                    Transaction (record), TransactionType, Summary
  repository/               storage interface, CSV implementation, BudgetStore
  service/                  TransactionService + ValidationException / NotFoundException
  web/                      ApiServer, StaticHandler, Json (small reader/writer)
  util/Csv.java             CSV quoting/parsing
src/main/resources/static/  the frontend
src/test/java/.../AppTests.java   tests (plain Java, no JUnit needed)
```

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/transactions?month=2026-11` | List transactions, newest first (omit `month` for all) |
| POST | `/api/transactions` | Create |
| GET / PUT / DELETE | `/api/transactions/{id}` | Read / update / delete one |
| GET | `/api/summary?month=2026-11` | Income, expense, balance, budget, spending by category |
| GET / PUT | `/api/budget` | Read / set the monthly budget (`{"budget": 15000}`) |
| GET | `/api/categories` | Default category names |
| GET | `/api/export?month=2026-11` | Download CSV |

Example:

```bash
curl -X POST localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"type":"EXPENSE","title":"Lunch","category":"Food","amount":"180.50","date":"2026-11-14","note":""}'
```

Errors come back as `{"error": "..."}` with status 400 (bad input), 404 (unknown id), 405, 415 (not JSON) or 500.

## Design decisions worth knowing (good interview talking points)

- **Layered architecture.** The service only knows the `TransactionRepository` interface, so the CSV file can be replaced by a database without touching business rules or the API.
- **Money uses `BigDecimal`**, rounded to 2 decimals, never `double`.
- **Crash-safe saving.** Each save writes a temp file and then moves it into place, and memory is updated only after the file write succeeds.
- **Thread safety.** The server handles requests on a thread pool; repository methods are synchronized.
- **Security basics.** Listens on localhost only; rejects foreign `Host` headers (DNS rebinding); requires `Content-Type: application/json` on writes; caps request size; the frontend builds the page with `textContent` so entered text can never run as HTML; a Content-Security-Policy is set; CSV export neutralises spreadsheet formulas (`=`, `+`, `-`, `@`).
- **Tests.** `AppTests` covers CSV and JSON handling, persistence across restarts, validation rules, monthly totals, and the whole HTTP API.

## Ideas to extend it (pick one for the next version)

1. Swap the CSV repository for a **SQLite or MySQL** one with JDBC (implement `TransactionRepository`).
2. Add **user accounts** with login and per-user data.
3. Add **recurring transactions** (rent, subscriptions) created automatically each month.
4. Per-category budgets and alerts when a category passes its limit.
5. Port the backend to **Spring Boot** and keep the same REST API and frontend unchanged.
6. Charts over time (monthly trend line).
