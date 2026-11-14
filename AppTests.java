package com.geetansh.expensetracker;

import com.geetansh.expensetracker.model.Summary;
import com.geetansh.expensetracker.model.Transaction;
import com.geetansh.expensetracker.model.TransactionType;
import com.geetansh.expensetracker.repository.BudgetStore;
import com.geetansh.expensetracker.repository.CsvTransactionRepository;
import com.geetansh.expensetracker.service.NotFoundException;
import com.geetansh.expensetracker.service.TransactionService;
import com.geetansh.expensetracker.service.ValidationException;
import com.geetansh.expensetracker.util.Csv;
import com.geetansh.expensetracker.web.ApiServer;
import com.geetansh.expensetracker.web.Json;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Dependency-free tests. Run with test.sh; exits with code 1 if anything fails. */
public class AppTests {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        run("Csv round-trips commas and quotes", AppTests::csvRoundTrip);
        run("Json parses and writes nested values", AppTests::jsonRoundTrip);
        run("Json rejects bad input", AppTests::jsonRejectsBad);
        run("Repository persists across restarts", AppTests::repositoryPersists);
        run("Service validates input", AppTests::serviceValidates);
        run("Service computes monthly summary", AppTests::serviceSummary);
        run("Service export neutralises formulas", AppTests::exportSafe);
        run("HTTP API end to end", AppTests::httpEndToEnd);
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ tests

    static void csvRoundTrip() {
        String tricky = "Dal, \"tadka\" & rice";
        List<String> back = Csv.parseLine(Csv.join(List.of("1", tricky, "")));
        eq(List.of("1", tricky, ""), back);
    }

    static void jsonRoundTrip() {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, 2.5, true, null], \"b\": \"x\\ny\\u0041\", \"c\": {}}");
        eq("[1, 2.5, true, null]", m.get("a").toString());
        eq("x\nyA", m.get("b"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("k", "q\"");
        out.put("n", new BigDecimal("3.50"));
        eq("{\"k\":\"q\\\"\",\"n\":3.50}", Json.write(out));
    }

    static void jsonRejectsBad() {
        for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "{\"a\":1} extra", "nope"}) {
            boolean threw = false;
            try {
                Json.parse(bad);
            } catch (Json.JsonException e) {
                threw = true;
            }
            check(threw, "should reject: " + bad);
        }
    }

    static void repositoryPersists() throws Exception {
        Path dir = Files.createTempDirectory("et-test");
        CsvTransactionRepository r1 = new CsvTransactionRepository(dir.resolve("e.csv"));
        Transaction saved = r1.save(tx(TransactionType.EXPENSE, "Chai, \"special\"", "Food", "25.50", "2026-11-02"));
        eq(1L, saved.id());
        r1.save(tx(TransactionType.INCOME, "Pocket money", "Salary", "1000", "2026-11-01"));
        r1.deleteById(1);
        CsvTransactionRepository r2 = new CsvTransactionRepository(dir.resolve("e.csv"));
        eq(1, r2.findAll().size());
        eq(2L, r2.findAll().get(0).id());
        eq(3L, r2.save(tx(TransactionType.EXPENSE, "Next", "Food", "1", "2026-11-03")).id());
    }

    static void serviceValidates() throws Exception {
        TransactionService s = newService();
        expectInvalid(s, tx(TransactionType.EXPENSE, "  ", "Food", "10", "2026-11-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "A", "", "10", "2026-11-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "A", "Food", "0", "2026-11-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "A", "Food", "-5", "2026-11-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "A", "Food", "0.001", "2026-11-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "A", "Food", "10", "1999-01-01"));
        expectInvalid(s, tx(TransactionType.EXPENSE, "x".repeat(81), "Food", "10", "2026-11-01"));
        Transaction ok = s.create(tx(TransactionType.EXPENSE, "  Tea \n break ", "Food", "10.456", "2026-11-01"));
        eq("Tea break", ok.title());
        eq(new BigDecimal("10.46"), ok.amount());
        boolean notFound = false;
        try {
            s.delete(999);
        } catch (NotFoundException e) {
            notFound = true;
        }
        check(notFound, "delete of unknown id should be NotFound");
    }

    static void serviceSummary() throws Exception {
        TransactionService s = newService();
        s.setBudget(new BigDecimal("5000"));
        s.create(tx(TransactionType.INCOME, "Allowance", "Salary", "10000", "2026-11-01"));
        s.create(tx(TransactionType.EXPENSE, "Rent", "Rent", "4000", "2026-11-02"));
        s.create(tx(TransactionType.EXPENSE, "Lunch", "Food", "150.25", "2026-11-03"));
        s.create(tx(TransactionType.EXPENSE, "Dinner", "Food", "200", "2026-11-04"));
        s.create(tx(TransactionType.EXPENSE, "October bill", "Bills", "999", "2026-10-30"));
        Summary nov = s.summary(YearMonth.of(2026, 11));
        eq(new BigDecimal("10000.00"), nov.income());
        eq(new BigDecimal("4350.25"), nov.expense());
        eq(new BigDecimal("5649.75"), nov.balance());
        eq(4, nov.count());
        eq("[Rent, Food]", nov.expenseByCategory().keySet().toString());
        eq(new BigDecimal("350.25"), nov.expenseByCategory().get("Food"));
        eq(new BigDecimal("5000.00"), nov.budget());
        eq(5, s.summary(null).count());
        eq("Dinner", s.list(YearMonth.of(2026, 11)).get(0).title());   // newest first
    }

    static void exportSafe() throws Exception {
        TransactionService s = newService();
        s.create(tx(TransactionType.EXPENSE, "=HYPERLINK(\"evil\")", "Food", "1", "2026-11-01"));
        String csv = s.exportCsv(null);
        check(csv.contains("'=HYPERLINK"), "formula should be prefixed with a quote: " + csv);
    }

    static void httpEndToEnd() throws Exception {
        TransactionService service = newService();
        ApiServer server = new ApiServer(service);
        server.start(0);
        try {
            String base = "http://localhost:" + server.port();
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> page = get(http, base + "/");
            eq(200, page.statusCode());
            check(page.body().contains("Expense"), "index.html should be served");
            eq(404, get(http, base + "/../secret.txt").statusCode());
            eq(404, get(http, base + "/index.java").statusCode());

            String body = "{\"type\":\"EXPENSE\",\"title\":\"Books\",\"category\":\"Education\","
                    + "\"amount\":499.5,\"date\":\"2026-11-10\",\"note\":\"\"}";
            HttpResponse<String> created = send(http, "POST", base + "/api/transactions", body, "application/json");
            eq(201, created.statusCode());
            long id = ((BigDecimal) Json.parseObject(created.body()).get("id")).longValue();

            eq(415, send(http, "POST", base + "/api/transactions", body, "text/plain").statusCode());
            eq(400, send(http, "POST", base + "/api/transactions", "{\"type\":\"EXPENSE\"}", "application/json").statusCode());
            eq(400, send(http, "POST", base + "/api/transactions", "not json", "application/json").statusCode());
            eq(400, get(http, base + "/api/transactions?month=banana").statusCode());

            String upd = body.replace("Books", "Novels");
            HttpResponse<String> updated = send(http, "PUT", base + "/api/transactions/" + id, upd, "application/json");
            eq(200, updated.statusCode());
            check(updated.body().contains("Novels"), "update should return new title");

            HttpResponse<String> list = get(http, base + "/api/transactions?month=2026-11");
            check(list.body().contains("Novels"), "list should contain updated item");
            eq("[]", get(http, base + "/api/transactions?month=2025-01").body());

            eq(200, send(http, "PUT", base + "/api/budget", "{\"budget\":3000}", "application/json").statusCode());
            HttpResponse<String> sum = get(http, base + "/api/summary?month=2026-11");
            Map<String, Object> s = Json.parseObject(sum.body());
            eq(new BigDecimal("499.50"), s.get("expense"));
            eq(new BigDecimal("3000.00"), s.get("budget"));

            HttpResponse<String> csv = get(http, base + "/api/export?month=2026-11");
            check(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"), "csv content type");
            check(csv.body().contains("Novels"), "csv contains row");

            eq(204, send(http, "DELETE", base + "/api/transactions/" + id, null, null).statusCode());
            eq(404, send(http, "DELETE", base + "/api/transactions/" + id, null, null).statusCode());
            eq(405, send(http, "PATCH", base + "/api/transactions/" + id, "{}", "application/json").statusCode());

            // Host header check (DNS rebinding protection): HttpClient forbids setting Host, so use a raw socket
            try (java.net.Socket sock = new java.net.Socket("127.0.0.1", server.port())) {
                sock.getOutputStream().write(("GET /api/categories HTTP/1.1\r\nHost: evil.example\r\n"
                        + "Connection: close\r\n\r\n").getBytes());
                String first = new String(sock.getInputStream().readAllBytes()).split("\r\n")[0];
                check(first.contains("403"), "foreign Host should be 403 but was: " + first);
            }
        } finally {
            server.stop();
        }
    }

    // ------------------------------------------------------------ helpers

    static TransactionService newService() throws Exception {
        Path dir = Files.createTempDirectory("et-test");
        return new TransactionService(new CsvTransactionRepository(dir.resolve("e.csv")),
                new BudgetStore(dir.resolve("s.properties")));
    }

    static Transaction tx(TransactionType type, String title, String cat, String amount, String date) {
        return new Transaction(0, type, title, cat, new BigDecimal(amount), LocalDate.parse(date), "");
    }

    static void expectInvalid(TransactionService s, Transaction t) {
        try {
            s.create(t);
        } catch (ValidationException e) {
            return;
        }
        throw new AssertionError("expected ValidationException for " + t);
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(HttpClient c, String method, String url, String body, String type) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (type != null) b.header("Content-Type", type);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void run(String name, ThrowingRunnable r) {
        try {
            r.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "  ->  " + t);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
