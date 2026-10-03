package asia.creat.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentJson {
    private final ObjectMapper mapper;

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new AgentFailure("SERIALIZATION_FAILED"); }
    }

    public JsonNode object(String text, Set<String> fields) {
        if (text == null || text.length() > 16000) throw new AgentFailure("INVALID_JSON");
        try (JsonParser parser = mapper.getFactory().createParser(text)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            parser.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
            parser.enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature());
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw new AgentFailure("INVALID_JSON");
            var names = node.fieldNames();
            while (names.hasNext()) if (!fields.contains(names.next())) throw new AgentFailure("UNKNOWN_ARGUMENT");
            return node;
        } catch (AgentFailure e) { throw e; }
        catch (Exception e) { throw new AgentFailure("INVALID_JSON"); }
    }

    public List<AgentData.Dependency> dependencies(String text) {
        try { return mapper.readValue(text, new TypeReference<>() { }); }
        catch (Exception e) { throw new AgentFailure("INVALID_STORED_DEPENDENCIES"); }
    }

    public List<AgentData.Source> sources(String text) {
        try { return mapper.readValue(text, new TypeReference<>() { }); }
        catch (Exception e) { throw new AgentFailure("INVALID_STORED_SOURCES"); }
    }
}
