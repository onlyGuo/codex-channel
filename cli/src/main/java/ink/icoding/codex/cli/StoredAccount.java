package ink.icoding.codex.cli;

import ink.icoding.codex.core.oauth.OpenAiAccount;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;
import java.nio.file.Path;

record StoredAccount(
        AccountMetadata metadata,
        OpenAiTokenResponse token,
        OpenAiAccount account,
        Path directory) {
}
