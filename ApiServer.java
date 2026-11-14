package com.geetansh.expensetracker.web;

import com.geetansh.expensetracker.model.Summary;
import com.geetansh.expensetracker.model.Transaction;
import com.geetansh.expensetracker.model.TransactionType;
import com.geetansh.expensetracker.service.NotFoundException;
import com.geetansh.expensetracker.service.TransactionService;
import com.geetansh.expensetracker.service.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP layer: a small REST API under /api plus static files for the web UI.
 * Listens on localhost only, because this is a personal, single-user app.
 */
public class ApiServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final TransactionService service;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(TransactionService service) {
        this.service = service;
    }

    /** @param port 0 picks a free port */
    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api", this::handleApi);
        server.createContext("/", new StaticHandler());
        executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    // ------------------------------------------------------------------ routing

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) {
                sendJson(ex, 403, Map.of("error", "Forbidden host"));
                return;
            }
            String method = ex.getRequestMethod();
            List<String> seg = segments(ex.getRequestURI().getPath());
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

            if (seg.isEmpty()) {
                sendJson(ex, 404, Map.of("error", "Not found"));
                return;
            }

            switch (seg.get(0)) {
                case "transactions" -> transactions(ex, method, seg, query);
                case "summary" -> {
                    requireMethod(method, "GET");
                    Summary s = service.summary(month(query));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("month", s.month());
                    out.put("income", s.income());
                    out.put("expense", s.expense());
                    out.put("balance", s.balance());
                    out.put("budget", s.budget());
                    out.put("count", s.count());
                    out.put("expenseByCategory", s.expenseByCategory());
                    sendJson(ex, 200, out);
                }
                case "budget" -> {
                    if (method.equals("GET")) {
                        sendJson(ex, 200, Map.of("budget", service.getBudget()));
                    } else if (method.equals("PUT")) {
                        Map<String, Object> body = readJsonBody(ex);
                        BigDecimal v = toDecimal(body.get("budget"));
                        sendJson(ex, 200, Map.of("budget", service.setBudget(v)));
                    } else {
                        throw new MethodNotAllowed();
                    }
                }
                case "categories" -> {
                    requireMethod(method, "GET");
                    sendJson(ex, 200, TransactionService.DEFAULT_CATEGORIES);
                }
                case "export" -> {
                    requireMethod(method, "GET");
                    YearMonth m = month(query);
                    String name = "expenses-" + (m == null ? "all" : m.toString()) + ".csv";
                    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
                    send(ex, 200, "text/csv; charset=utf-8", service.exportCsv(m).getBytes(StandardCharsets.UTF_8));
                }
                default -> sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (ValidationException | Json.JsonException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (NotFoundException e) {
            sendJson(ex, 404, Map.of("error", e.getMessage()));
        } catch (MethodNotAllowed e) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
        } catch (UnsupportedMedia e) {
            sendJson(ex, 415, Map.of("error", "Content-Type must be application/json"));
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Something went wrong on the server."));
        } finally {
            ex.close();
        }
    }

    private void transactions(HttpExchange ex, String method, List<String> seg, Map<String, String> query)
            throws IOException {
        if (seg.size() == 1) {
            switch (method) {
                case "GET" -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Transaction t : service.list(month(query))) out.add(view(t));
                    sendJson(ex, 200, out);
                }
                case "POST" -> {
                    Transaction created = service.create(readTransaction(readJsonBody(ex)));
                    sendJson(ex, 201, view(created));
                }
                default -> throw new MethodNotAllowed();
            }
            return;
        }
        long id = parseId(seg.get(1));
        switch (method) {
            case "GET" -> sendJson(ex, 200, view(service.get(id)));
            case "PUT" -> sendJson(ex, 200, view(service.update(id, readTransaction(readJsonBody(ex)))));
            case "DELETE" -> {
                service.delete(id);
                ex.sendResponseHeaders(204, -1);
            }
            default -> throw new MethodNotAllowed();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class MethodNotAllowed extends RuntimeException {}

    private static final class UnsupportedMedia extends RuntimeException {}

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new MethodNotAllowed();
    }

    /** Blocks DNS-rebinding style requests: only localhost host names are served. */
    private static boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        int colon = host.lastIndexOf(':');
        String name = colon >= 0 ? host.substring(0, colon) : host;
        return name.equals("localhost") || name.equals("127.0.0.1");
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        String rest = path.length() > 4 ? path.substring(4) : "";   // strip "/api"
        for (String s : rest.split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    private static YearMonth month(Map<String, String> query) {
        String m = query.get("month");
        if (m == null || m.isBlank() || m.equals("all")) return null;
        try {
            return YearMonth.parse(m);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Month must look like 2026-11.");
        }
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new NotFoundException("Transaction " + s + " not found");
        }
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) throw new UnsupportedMedia();
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
            if (data.length > MAX_BODY_BYTES) throw new ValidationException("Request is too large.");
            return Json.parseObject(new String(data, StandardCharsets.UTF_8));
        }
    }

    private static Transaction readTransaction(Map<String, Object> m) {
        String typeText = text(m, "type");
        TransactionType type;
        try {
            type = typeText == null ? null : TransactionType.valueOf(typeText.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Type must be INCOME or EXPENSE.");
        }
        LocalDate date;
        String dateText = text(m, "date");
        try {
            date = dateText == null ? null : LocalDate.parse(dateText.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("Date must look like 2026-11-14.");
        }
        return new Transaction(0, type, text(m, "title"), text(m, "category"),
                toDecimal(m.get("amount")), date, text(m, "note"));
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new ValidationException("'" + key + "' must be text.");
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Amount must be a number.");
            }
        }
        throw new ValidationException("Amount must be a number.");
    }

    private static Map<String, Object> view(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type());
        m.put("title", t.title());
        m.put("category", t.category());
        m.put("amount", t.amount());
        m.put("date", t.date());
        m.put("note", t.note());
        return m;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
