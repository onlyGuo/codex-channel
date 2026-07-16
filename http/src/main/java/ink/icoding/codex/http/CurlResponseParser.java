package ink.icoding.codex.http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CurlResponseParser {

    private static final Pattern STATUS = Pattern.compile("^HTTP/\\S+\\s+(\\d{3})(?:\\s+.*)?$");

    private CurlResponseParser() {
    }

    static ChromeHttpResponse.Metadata parse(Path headerFile, URI effectiveUri) throws IOException {
        String raw = Files.readString(headerFile, StandardCharsets.ISO_8859_1)
                .replace("\r\n", "\n");
        ParsedHeaders selected = null;
        for (String block : raw.split("\n\n")) {
            ParsedHeaders parsed = parseBlock(block);
            if (parsed != null) {
                selected = parsed;
            }
        }
        if (selected == null) {
            throw new ChromeHttpException("curl returned no valid HTTP response headers", -1);
        }
        return new ChromeHttpResponse.Metadata(selected.statusCode(), effectiveUri, selected.headers());
    }

    private static ParsedHeaders parseBlock(String block) {
        String[] lines = block.split("\n");
        if (lines.length == 0) {
            return null;
        }
        Matcher matcher = STATUS.matcher(lines[0].trim());
        if (!matcher.matches()) {
            return null;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String lastName = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if ((line.startsWith(" ") || line.startsWith("\t")) && lastName != null) {
                List<String> values = headers.get(lastName);
                int lastIndex = values.size() - 1;
                values.set(lastIndex, values.get(lastIndex) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String existing = headers.keySet().stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(name))
                    .findFirst().orElse(name);
            headers.computeIfAbsent(existing, ignored -> new ArrayList<>())
                    .add(line.substring(colon + 1).trim());
            lastName = existing;
        }
        return new ParsedHeaders(Integer.parseInt(matcher.group(1)), headers);
    }

    private record ParsedHeaders(int statusCode, Map<String, List<String>> headers) {
    }
}
