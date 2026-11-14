package com.geetansh.expensetracker.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Serves the web UI from src/main/resources/static (packaged on the classpath). */
class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml");

    private static final String CSP = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; frame-ancestors 'none'";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                ApiServer.send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes());
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            int dot = path.lastIndexOf('.');
            String ext = dot < 0 ? "" : path.substring(dot + 1);
            if (path.contains("..") || path.contains("\\") || !TYPES.containsKey(ext)) {
                ApiServer.send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes());
                return;
            }
            try (InputStream in = StaticHandler.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    ApiServer.send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes());
                    return;
                }
                ex.getResponseHeaders().set("Content-Security-Policy", CSP);
                ApiServer.send(ex, 200, TYPES.get(ext), in.readAllBytes());
            }
        } finally {
            ex.close();
        }
    }
}
