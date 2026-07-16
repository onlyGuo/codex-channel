package ink.icoding.codex.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;

final class CliOutput {

    private final ObjectMapper mapper;
    private final boolean json;
    private final PrintWriter out;

    CliOutput(ObjectMapper mapper, boolean json, PrintWriter out) {
        this.mapper = mapper;
        this.json = json;
        this.out = out;
    }

    boolean json() {
        return json;
    }

    void value(Object value) {
        if (json) {
            try {
                out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            } catch (JsonProcessingException exception) {
                throw new CliException("Could not serialize output", exception);
            }
        } else {
            out.println(value);
        }
        out.flush();
    }

    void line(String value) {
        out.println(value);
        out.flush();
    }
}
