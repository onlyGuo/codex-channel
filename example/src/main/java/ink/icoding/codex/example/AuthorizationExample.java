package ink.icoding.codex.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ink.icoding.codex.core.oauth.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal example of the authorization, token parsing, and account enrichment flow. */
public final class AuthorizationExample {

    private AuthorizationExample() {
    }

    public static void main(String[] args) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();


//        OpenAiAuthorizationUrlGenerator authorizationGenerator = new OpenAiAuthorizationUrlGenerator();
//        AuthorizationSession session = authorizationGenerator.create();
//        System.out.println(session.authorizationUrl());
//        System.out.println(session.toMap());

//        OpenAiTokenClient openAiTokenClient = new OpenAiTokenClient();
//        OpenAiTokenResponse openAiTokenResponse = openAiTokenClient.exchangeAuthorizationCode(
//                "",
//                "http://localhost:1455/auth/callback",
//                "");
//
//        System.out.println(openAiTokenResponse);
//        // 写到文件
//        objectMapper.writerWithDefaultPrettyPrinter()
//                .writeValue(exampleFile("opentoken.json").toFile(), openAiTokenResponse);
//
//        OpenAiIdTokenParser idTokenParser = new OpenAiIdTokenParser();
//        OpenAiAccount account = idTokenParser.parse(openAiTokenResponse);
//        System.out.println(account);
//        objectMapper.writerWithDefaultPrettyPrinter()
//                .writeValue(exampleFile("openaccount.json").toFile(), account);




//        HttpProxyConfig proxyConfig = new HttpProxyConfig("127.0.0.1", 8080, "username", "password");
//        HttpProxyConfig proxy = new HttpProxyConfig("127.0.0.1", 8081);
        try (ChatGptAccountClient accountClient = new ChatGptAccountClient(
                exampleFile("opentoken.json").toFile(), exampleFile("openaccount.json").toFile())) {
            OpenAiAccount enrichedAccount = accountClient.account();
            System.out.println(enrichedAccount);
            System.out.println(accountClient.fetchQuotaInfo());
            OpenAiRateLimitResetCredits resetCredits = accountClient.fetchRateLimitResetCredits();
            System.out.println(resetCredits);
//        accountClient.streamResponses(
//                objectMapper.readTree(
//                        exampleFile("request.json").toFile()), new OpenAiResponsesListener() {
//                    @Override
//                    public void onEvent(OpenAiResponsesEvent event) {
//                        System.out.println(event.event() + "----" + event.data());
//                    }
//                });

            accountClient.streamChatCompletions(
                    objectMapper.readTree(exampleFile("chat-request.json").toFile()),
                    System.out::println);

//        System.out.println(accountClient.fetchModels("0.144.2"));
//        System.out.println(accountClient.fetchUsageJson());
//        System.out.println(accountClient.fetchProfile());
//        System.out.println(accountClient.fetchWorkspaceMessages());
//        System.out.println(accountClient.fetchConfigBundle());
//        System.out.println(accountClient.createResponse(
//                objectMapper.readTree(exampleFile("request.json").toFile())));
//        System.out.println(accountClient.createChatCompletion(
//                objectMapper.readTree(exampleFile("chat-request.json").toFile())));
//        accountClient.streamResponsesWebSocket(
//                objectMapper.readTree(exampleFile("request.json").toFile()),
//                event -> System.out.println(event.event() + "----" + event.data()));
//        accountClient.streamChatCompletionsWebSocket(
//                objectMapper.readTree(exampleFile("chat-request.json").toFile()),
//                System.out::println);

        // Revocation permanently disables refresh for this client; only run it as the final test.
//            accountClient.revokeToken();
        }
    }

    private static Path exampleFile(String filename) {
        Path moduleFile = Path.of("example", "data", filename);
        return Files.isRegularFile(moduleFile) ? moduleFile : Path.of("data", filename);
    }
}
