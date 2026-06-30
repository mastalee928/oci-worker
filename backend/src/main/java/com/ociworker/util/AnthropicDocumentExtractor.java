package com.ociworker.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Slf4j
public final class AnthropicDocumentExtractor {

    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EXTRACTED_CHARS = 80_000;
    private static final int MAX_BASE64_CHARS = ((MAX_DOCUMENT_BYTES + 2) / 3) * 4 + 16;
    private static final int MAX_SQLITE_TABLES = 30;
    private static final int MAX_SQLITE_SAMPLE_ROWS = 3;
    private static final int MAX_SQLITE_COLUMNS = 25;
    private static final int MAX_SQLITE_CELL_CHARS = 300;
    private static final int MAX_URL_REDIRECTS = 3;
    private static final Duration URL_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration URL_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient URL_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(URL_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private AnthropicDocumentExtractor() {
    }

    public static String extract(String sourceType, String mediaType, String fileName, String data) {
        String safeMediaType = blankToNull(mediaType);
        String safeFileName = blankToNull(fileName);
        if (sourceType == null || sourceType.isBlank()) {
            return logAndReturn("unknown", safeMediaType, safeFileName, -1,
                    unsupported("document", "缺少 source.type"));
        }
        if ("text".equalsIgnoreCase(sourceType)) {
            return logAndReturn("text", safeMediaType, safeFileName, utf8Length(data),
                    formatExtractedText(data, safeMediaType, safeFileName, false));
        }
        if ("url".equalsIgnoreCase(sourceType)) {
            if (data == null || data.isBlank()) {
                return logAndReturn("url", safeMediaType, safeFileName, -1,
                        unsupported("document", "URL 为空"));
            }
            try {
                RemoteDocument remote = downloadRemoteDocument(data.trim());
                String effectiveMediaType = firstNonBlank(safeMediaType, remote.mediaType());
                String effectiveFileName = firstNonBlank(safeFileName, remote.fileName());
                return logAndReturn("url", effectiveMediaType, effectiveFileName, remote.bytes().length, extractBytes(
                        remote.bytes(),
                        effectiveMediaType,
                        effectiveFileName));
            } catch (RemoteDocumentException e) {
                return logAndReturn("url", safeMediaType, safeFileName, -1,
                        unsupported("document", e.getMessage()));
            }
        }
        if (!"base64".equalsIgnoreCase(sourceType)) {
            return logAndReturn(sourceType, safeMediaType, safeFileName, -1,
                    unsupported("document", "暂不支持 source.type=" + sourceType));
        }
        if (data == null || data.isBlank()) {
            return logAndReturn("base64", safeMediaType, safeFileName, -1,
                    unsupported("document", "base64 内容为空"));
        }
        if (data.length() > MAX_BASE64_CHARS) {
            return logAndReturn("base64", safeMediaType, safeFileName, -1,
                    unsupported("document", "文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限"));
        }
        byte[] bytes;
        try {
            bytes = decodeBase64(data);
        } catch (IllegalArgumentException e) {
            return logAndReturn("base64", safeMediaType, safeFileName, -1,
                    unsupported("document", "base64 解码失败"));
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            return logAndReturn("base64", safeMediaType, safeFileName, bytes.length,
                    unsupported("document", "文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限"));
        }
        return logAndReturn("base64", safeMediaType, safeFileName, bytes.length,
                extractBytes(bytes, safeMediaType, safeFileName));
    }

    private static String extractBytes(byte[] bytes, String mediaType, String fileName) {
        if (isSqliteDatabase(mediaType, fileName)) {
            return extractSqliteDatabase(bytes, mediaType, fileName);
        }
        String directText = tryDecodeTextLike(bytes, mediaType, fileName);
        if (directText != null) {
            return formatExtractedText(directText, mediaType, fileName, directText.length() > MAX_EXTRACTED_CHARS);
        }
        return parseWithTika(bytes, mediaType, fileName);
    }

    private static boolean isSqliteDatabase(String mediaType, String fileName) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return type.contains("sqlite")
                || type.contains("x-sqlite3")
                || name.endsWith(".sqlite")
                || name.endsWith(".sqlite3")
                || name.endsWith(".db");
    }

    private static String extractSqliteDatabase(byte[] bytes, String mediaType, String fileName) {
        Path temp = null;
        try {
            temp = Files.createTempFile("ociworker-sqlite-", ".db");
            Files.write(temp, bytes);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + temp.toAbsolutePath())) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA query_only = ON");
                }
                String summary = sqliteSummary(conn);
                return formatExtractedText(summary, mediaType, fileName, summary.length() > MAX_EXTRACTED_CHARS);
            }
        } catch (Exception e) {
            return unsupported("document", "SQLite 数据库解析失败：" + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String sqliteSummary(Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("SQLite 数据库只读摘要。可根据以下表结构和样例数据生成 SELECT/UPDATE/ALTER 等 SQL，但不会自动修改数据库。\n");
        List<SqliteObject> objects = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT type, name, sql
                     FROM sqlite_master
                     WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%'
                     ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name
                     """)) {
            while (rs.next() && objects.size() < MAX_SQLITE_TABLES) {
                objects.add(new SqliteObject(rs.getString("type"), rs.getString("name"), rs.getString("sql")));
            }
        }
        if (objects.isEmpty()) {
            sb.append("未发现用户表或视图。");
            return sb.toString();
        }
        sb.append("对象数量：").append(objects.size());
        if (objects.size() == MAX_SQLITE_TABLES) {
            sb.append("（已限制为前 ").append(MAX_SQLITE_TABLES).append(" 个）");
        }
        sb.append('\n');
        for (SqliteObject object : objects) {
            sb.append("\n## ").append(object.type()).append(": ").append(object.name()).append('\n');
            if (object.sql() != null && !object.sql().isBlank()) {
                sb.append("DDL: ").append(compact(object.sql(), 2000)).append('\n');
            }
            appendSqliteColumns(conn, sb, object.name());
            appendSqliteSampleRows(conn, sb, object.name());
        }
        return sb.toString();
    }

    private static void appendSqliteColumns(Connection conn, StringBuilder sb, String tableName) throws SQLException {
        sb.append("Columns:\n");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            int count = 0;
            boolean truncated = false;
            while (rs.next()) {
                if (count >= MAX_SQLITE_COLUMNS) {
                    truncated = true;
                    break;
                }
                sb.append("- ")
                        .append(rs.getString("name"))
                        .append(' ')
                        .append(firstNonBlank(rs.getString("type"), "UNKNOWN"));
                if (rs.getInt("notnull") == 1) {
                    sb.append(" NOT NULL");
                }
                if (rs.getInt("pk") > 0) {
                    sb.append(" PRIMARY KEY");
                }
                String defaultValue = rs.getString("dflt_value");
                if (defaultValue != null && !defaultValue.isBlank()) {
                    sb.append(" DEFAULT ").append(compact(defaultValue, 120));
                }
                sb.append('\n');
                count++;
            }
            if (truncated) {
                sb.append("- ... 字段过多，已省略后续字段\n");
            }
        }
    }

    private static void appendSqliteSampleRows(Connection conn, StringBuilder sb, String tableName) {
        sb.append("Sample rows:\n");
        String sql = "SELECT * FROM " + quoteIdentifier(tableName) + " LIMIT " + MAX_SQLITE_SAMPLE_ROWS;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = Math.min(meta.getColumnCount(), MAX_SQLITE_COLUMNS);
            int row = 0;
            while (rs.next()) {
                row++;
                sb.append("- row ").append(row).append(": ");
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        sb.append(", ");
                    }
                    sb.append(meta.getColumnLabel(i))
                            .append('=')
                            .append(compact(String.valueOf(rs.getObject(i)), MAX_SQLITE_CELL_CHARS));
                }
                if (meta.getColumnCount() > columns) {
                    sb.append(", ...");
                }
                sb.append('\n');
            }
            if (row == 0) {
                sb.append("- 无样例行\n");
            }
        } catch (SQLException e) {
            sb.append("- 样例行读取失败：").append(e.getMessage()).append('\n');
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static String compact(String value, int max) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > max ? normalized.substring(0, max) + "..." : normalized;
    }

    private static RemoteDocument downloadRemoteDocument(String url) throws RemoteDocumentException {
        URI uri = parseAndValidateRemoteUri(url);
        for (int redirects = 0; redirects <= MAX_URL_REDIRECTS; redirects++) {
            validateRemoteUri(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(URL_REQUEST_TIMEOUT)
                    .header("User-Agent", "OCIworker/1.0 document-fetcher")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = URL_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemoteDocumentException("远程文件下载被中断");
            } catch (IOException | IllegalArgumentException e) {
                throw new RemoteDocumentException("远程文件下载失败：" + e.getMessage());
            }
            int status = response.statusCode();
            if (isRedirectStatus(status)) {
                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response.body());
                if (location == null || location.isBlank()) {
                    throw new RemoteDocumentException("远程文件跳转缺少 Location");
                }
                if (redirects == MAX_URL_REDIRECTS) {
                    throw new RemoteDocumentException("远程文件跳转超过 " + MAX_URL_REDIRECTS + " 次上限");
                }
                uri = uri.resolve(location.trim());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new RemoteDocumentException("远程文件返回 HTTP " + status);
            }
            long length = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (length > MAX_DOCUMENT_BYTES) {
                closeQuietly(response.body());
                throw new RemoteDocumentException("远程文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限");
            }
            byte[] body = readLimited(response.body());
            String mediaType = response.headers().firstValue("content-type")
                    .map(AnthropicDocumentExtractor::normalizeContentType)
                    .orElse(null);
            return new RemoteDocument(body, mediaType, fileNameFromUri(uri));
        }
        throw new RemoteDocumentException("远程文件跳转超过 " + MAX_URL_REDIRECTS + " 次上限");
    }

    private static URI parseAndValidateRemoteUri(String url) throws RemoteDocumentException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new RemoteDocumentException("URL 格式无效");
        }
        validateRemoteUri(uri);
        return uri;
    }

    private static void validateRemoteUri(URI uri) throws RemoteDocumentException {
        if (uri == null) {
            throw new RemoteDocumentException("URL 为空");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new RemoteDocumentException("暂不支持非 http/https URL");
        }
        if (uri.getUserInfo() != null) {
            throw new RemoteDocumentException("暂不支持带用户名密码的 URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RemoteDocumentException("URL 缺少主机名");
        }
        String safeHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(safeHost) || safeHost.endsWith(".localhost")) {
            throw new RemoteDocumentException("禁止访问内网或本机地址");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new RemoteDocumentException("URL 主机解析失败");
        }
        if (addresses.length == 0) {
            throw new RemoteDocumentException("URL 主机解析为空");
        }
        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address)) {
                throw new RemoteDocumentException("禁止访问内网或本机地址");
            }
        }
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int a = raw[0] & 0xff;
            int b = raw[1] & 0xff;
            return a == 0
                    || a == 10
                    || a == 127
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 198 && (b == 18 || b == 19));
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && second == 0x01 && (raw[2] & 0xff) == 0x0d && (raw[3] & 0xff) == 0xb8);
        }
        return false;
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readLimited(InputStream input) throws RemoteDocumentException {
        try (InputStream in = input) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOCUMENT_BYTES) {
                    throw new RemoteDocumentException("远程文件超过 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB 上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RemoteDocumentException("远程文件读取失败：" + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String value = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return value.isBlank() ? null : value.trim();
    }

    private static String fileNameFromUri(URI uri) {
        if (uri == null || uri.getPath() == null || uri.getPath().isBlank()) {
            return null;
        }
        String path = uri.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isBlank()) {
            return null;
        }
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return name;
        }
    }

    private static byte[] decodeBase64(String data) {
        String trimmed = data.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ignored) {
            return Base64.getMimeDecoder().decode(trimmed);
        }
    }

    private static String tryDecodeTextLike(byte[] bytes, String mediaType, String fileName) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (type.contains("officedocument")
                || type.contains("msword")
                || type.contains("ms-excel")
                || type.contains("ms-powerpoint")
                || type.contains("opendocument")
                || type.contains("pdf")
                || type.contains("zip")
                || name.endsWith(".doc")
                || name.endsWith(".docx")
                || name.endsWith(".xls")
                || name.endsWith(".xlsx")
                || name.endsWith(".ppt")
                || name.endsWith(".pptx")
                || name.endsWith(".pdf")
                || name.endsWith(".odt")
                || name.endsWith(".ods")
                || name.endsWith(".odp")) {
            return null;
        }
        boolean textLike = type.startsWith("text/")
                || type.contains("json")
                || type.contains("xml")
                || type.contains("yaml")
                || type.contains("csv")
                || type.contains("javascript")
                || type.contains("x-sh")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".markdown")
                || name.endsWith(".csv")
                || name.endsWith(".json")
                || name.endsWith(".jsonl")
                || name.endsWith(".yaml")
                || name.endsWith(".yml")
                || name.endsWith(".xml")
                || name.endsWith(".html")
                || name.endsWith(".htm")
                || name.endsWith(".log")
                || name.endsWith(".sql")
                || name.endsWith(".ini")
                || name.endsWith(".cfg")
                || name.endsWith(".conf")
                || name.endsWith(".config")
                || name.endsWith(".properties")
                || name.endsWith(".env")
                || name.endsWith(".toml")
                || name.endsWith(".java")
                || name.endsWith(".js")
                || name.endsWith(".jsx")
                || name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".css")
                || name.endsWith(".scss")
                || name.endsWith(".sass")
                || name.endsWith(".less")
                || name.endsWith(".vue")
                || name.endsWith(".svelte")
                || name.endsWith(".py")
                || name.endsWith(".go")
                || name.endsWith(".rs")
                || name.endsWith(".php")
                || name.endsWith(".rb")
                || name.endsWith(".c")
                || name.endsWith(".cc")
                || name.endsWith(".cpp")
                || name.endsWith(".h")
                || name.endsWith(".hpp")
                || name.endsWith(".cs")
                || name.endsWith(".kt")
                || name.endsWith(".kts")
                || name.endsWith(".swift")
                || name.endsWith(".lua")
                || name.endsWith(".r")
                || name.endsWith(".sh")
                || name.endsWith(".bash")
                || name.endsWith(".zsh")
                || name.endsWith(".fish")
                || name.endsWith(".bat")
                || name.endsWith(".cmd")
                || name.endsWith(".ps1")
                || name.endsWith("dockerfile")
                || name.endsWith(".dockerfile")
                || name.endsWith("makefile")
                || name.endsWith(".gitignore")
                || name.endsWith(".gitattributes")
                || name.endsWith(".editorconfig")
                || name.endsWith(".npmrc")
                || name.endsWith(".yarnrc");
        if (!textLike) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String parseWithTika(byte[] bytes, String mediaType, String fileName) {
        Metadata metadata = new Metadata();
        if (mediaType != null) {
            metadata.set(Metadata.CONTENT_TYPE, mediaType);
        }
        if (fileName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            String partial = handler.toString();
            if (partial != null && !partial.isBlank()) {
                return formatExtractedText(partial, mediaType, fileName, true);
            }
            return unsupported("document", "文本提取失败：" + e.getMessage());
        } catch (Exception e) {
            return unsupported("document", "文本提取失败：" + e.getMessage());
        }
        String text = handler.toString();
        if (text == null || text.isBlank()) {
            return unsupported("document", "未提取到可读文本，扫描版 PDF 或纯图片文档需要 OCR");
        }
        return formatExtractedText(text, mediaType, fileName, text.length() >= MAX_EXTRACTED_CHARS);
    }

    private static String formatExtractedText(String text, String mediaType, String fileName, boolean truncated) {
        String safeText = text == null ? "" : text.strip();
        if (safeText.length() > MAX_EXTRACTED_CHARS) {
            safeText = safeText.substring(0, MAX_EXTRACTED_CHARS);
            truncated = true;
        }
        if (safeText.isBlank()) {
            return unsupported("document", "文档内容为空");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[OCIworker 已提取文档文本");
        if (fileName != null && !fileName.isBlank()) {
            sb.append("，文件名：").append(fileName);
        }
        if (mediaType != null && !mediaType.isBlank()) {
            sb.append("，类型：").append(mediaType);
        }
        sb.append("]\n");
        sb.append(safeText);
        if (truncated) {
            sb.append("\n[OCIworker 提示：文档内容较长，已截断为前 ")
                    .append(MAX_EXTRACTED_CHARS)
                    .append(" 个字符。]");
        }
        return sb.toString();
    }

    private static String unsupported(String type, String reason) {
        return "[OCIworker 提示：暂无法解析 Anthropic " + type + " 内容块：" + reason + "。]";
    }

    private static String logAndReturn(String sourceType, String mediaType, String fileName, long bytes, String result) {
        boolean failed = result != null && result.startsWith("[OCIworker 提示：暂无法解析");
        boolean truncated = result != null && result.contains("已截断为前 ");
        log.info(
                "Oracle AI document extraction source={} kind={} file={} mediaType={} bytes={} chars={} truncated={} status={}",
                safeLog(sourceType),
                isSqliteDatabase(mediaType, fileName) ? "sqlite" : "document",
                safeLog(fileName),
                safeLog(mediaType),
                bytes,
                result == null ? 0 : result.length(),
                truncated,
                failed ? "failed" : "success");
        return result;
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String safeLog(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() > 120 ? sanitized.substring(0, 120) + "..." : sanitized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record RemoteDocument(byte[] bytes, String mediaType, String fileName) {
    }

    private record SqliteObject(String type, String name, String sql) {
    }

    private static final class RemoteDocumentException extends Exception {
        private RemoteDocumentException(String message) {
            super(message);
        }
    }
}
