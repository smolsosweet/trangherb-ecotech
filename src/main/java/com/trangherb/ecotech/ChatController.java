package com.trangherb.ecotech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
public class ChatController {
    private static final String SYSTEM_PROMPT =
            "Bạn là trợ lý tư vấn của TrangHerb EcoTech. Trả lời ngắn gọn, lịch sự, đúng trọng tâm. " +
            "Chỉ sử dụng thông tin từ tài liệu tham khảo. Nếu chưa đủ thông tin, hãy nói rõ và hỏi lại."
            + "Đừng đoán nếu không chắc chắn, hãy nói rõ bạn không biết. Luôn ưu tiên thông tin từ tài liệu tham khảo hơn là kiến thức chung."
            + "Nếu có nhiều tài liệu tham khảo, hãy tổng hợp thông tin từ tất cả chúng để trả lời. " +
            "Đừng đưa ra câu trả lời dài dòng, hãy đi thẳng vào vấn đề và trả lời ngắn gọn, súc tích. " +
            "Nếu câu hỏi không liên quan đến sản phẩm hoặc dịch vụ của chúng tôi, hãy lịch sự từ chối trả lời và hướng người hỏi đến trang web của chúng tôi để tìm thêm thông tin."
            + "Nếu câu hỏi liên quan đến giá cả, hãy cung cấp thông tin về phạm vi giá hoặc các yếu tố ảnh hưởng đến giá cả, nhưng đừng đưa ra con số cụ thể nếu không có trong tài liệu tham khảo."
            + "Nếu câu hỏi liên quan đến cách sử dụng sản phẩm, hãy cung cấp hướng dẫn cơ bản dựa trên thông tin trong tài liệu tham khảo, nhưng khuyến khích người hỏi đọc kỹ hướng dẫn sử dụng đi kèm với sản phẩm để đảm bảo an toàn và hiệu quả."
            + "Nếu câu hỏi liên quan đến chính sách bảo hành, hãy cung cấp thông tin về phạm vi bảo hành, thời gian bảo hành và các điều kiện áp dụng dựa trên thông tin trong tài liệu tham khảo, nhưng khuyến khích người hỏi liên hệ trực tiếp với bộ phận chăm sóc khách hàng của chúng tôi để biết thêm chi tiết và hỗ trợ cụ thể."
            + "Nếu câu hỏi liên quan tới thông tin về công nghiệp, công nghệ, xu hướng thị trường, chỉ trả lời khi có trong tài liệu tham khảo; nếu chưa có, hãy nói rõ và hướng người hỏi theo dõi trang web của chúng tôi để cập nhật.";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final DocxContextProvider contextProvider;
    private final String apiKey;
    private final String model;

    public ChatController(
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${GEMINI_MODEL:}") String model,
            @Value("${DOCX_PATHS:}") String docxPaths
    ) {
        EnvConfig env = EnvConfig.load(docxPaths);
        this.apiKey = firstNonBlank(apiKey, env.apiKey());
        this.model = firstNonBlank(model, env.model(), "gemini-2.0-flash-lite");
        String resolvedDocx = firstNonBlank(docxPaths, env.docxPaths(),
                "_Dự án hoàn chỉnh (1) (1).docx;Hệ sinh thái tuần hoàn thông minh (1).docx;goi_dich_vu.docx");
        this.contextProvider = new DocxContextProvider(resolvedDocx);
    }

    @PostMapping("/api/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request) {
        String message = request == null ? null : request.message();
        try {
            if (message == null || message.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(error("Message is required."));
            }

            if (apiKey == null || apiKey.isBlank()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(error("Server missing API key."));
            }

            String directAnswer = contextProvider.getDirectAnswerForQuery(message);
            if (directAnswer != null && !directAnswer.isBlank()) {
                Map<String, String> result = new HashMap<>();
                result.put("text", directAnswer);
                return ResponseEntity.ok(result);
            }

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    URLEncoder.encode(model, StandardCharsets.UTF_8) +
                    ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            ObjectNode payload = mapper.createObjectNode();
            ArrayNode contents = payload.putArray("contents");
            ObjectNode user = contents.addObject();
            user.put("role", "user");
            user.putArray("parts").addObject().put("text", message.trim());

            String context = contextProvider.getContextForQuery(message);
            String systemText = context.isBlank()
                    ? SYSTEM_PROMPT
                    : SYSTEM_PROMPT + "\n\nTài liệu tham khảo:\n" + context;

            ObjectNode systemInstruction = payload.putObject("systemInstruction");
            systemInstruction.putArray("parts").addObject().put("text", systemText);

            ObjectNode generationConfig = payload.putObject("generationConfig");
            generationConfig.put("temperature", 0.5);
            generationConfig.put("maxOutputTokens", 512);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String fallback = contextProvider.getAnswerForQuery(message);
                if (fallback != null && !fallback.isBlank()) {
                    Map<String, String> result = new HashMap<>();
                    result.put("text", fallback);
                    return ResponseEntity.ok(result);
                }
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(error("Gemini API error", response.body()));
            }

            String text = extractText(response.body());
            if (text.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(error("Empty response from Gemini."));
            }

            Map<String, String> result = new HashMap<>();
            result.put("text", text);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            String fallback = contextProvider.getAnswerForQuery(message == null ? "" : message);
            if (fallback != null && !fallback.isBlank()) {
                Map<String, String> result = new HashMap<>();
                result.put("text", fallback);
                return ResponseEntity.ok(result);
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(error("Server error", ex.getMessage()));
        }
    }

    private String extractText(String json) throws Exception {
        JsonNode data = mapper.readTree(json);
        JsonNode parts = data.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("\n");
                }
                builder.append(text.trim());
            }
        }
        return builder.toString().trim();
    }

    private Map<String, String> error(String message) {
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        return body;
    }

    private Map<String, String> error(String message, String detail) {
        Map<String, String> body = new HashMap<>();
        body.put("error", message);
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        return body;
    }

    public record ChatRequest(String message) {}

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record EnvConfig(String apiKey, String model, String docxPaths) {
        static EnvConfig load(String docxPathsFromProps) {
            String apiKey = System.getenv("GEMINI_API_KEY");
            String model = System.getenv("GEMINI_MODEL");
            String docxPaths = System.getenv("DOCX_PATHS");

            if ((apiKey == null || apiKey.isBlank()) ||
                    (model == null || model.isBlank()) ||
                    (docxPaths == null || docxPaths.isBlank())) {
                Optional<Map<String, String>> file = EnvConfig.readEnvFile(Path.of("trangherb.env"));
                if (file.isPresent()) {
                    Map<String, String> map = file.get();
                    if (apiKey == null || apiKey.isBlank()) {
                        apiKey = map.get("GEMINI_API_KEY");
                    }
                    if (model == null || model.isBlank()) {
                        model = map.get("GEMINI_MODEL");
                    }
                    if (docxPaths == null || docxPaths.isBlank()) {
                        docxPaths = map.get("DOCX_PATHS");
                    }
                }
            }

            if (docxPaths == null || docxPaths.isBlank()) {
                docxPaths = docxPathsFromProps;
            }
            return new EnvConfig(apiKey, model, docxPaths);
        }

        private static Optional<Map<String, String>> readEnvFile(Path path) {
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            Map<String, String> values = new HashMap<>();
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, idx).trim();
                    String value = trimmed.substring(idx + 1).trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        values.put(key, value);
                    }
                }
                return Optional.of(values);
            } catch (Exception ex) {
                return Optional.empty();
            }
        }
    }
}


