package ink.icoding.codex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.icoding.codex.core.oauth.OpenAiAccount;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {

    @TempDir Path temporaryDirectory;

    @Test
    void storesMultipleAccountsAndMaintainsTheActiveAccount() {
        AccountStore store = new AccountStore(temporaryDirectory.resolve("state"));

        StoredAccount first = store.save(null, token("one"), account("one@example.com"), false);
        StoredAccount second = store.save(null, token("two"), account("two@example.com"), false);

        assertEquals("one", first.metadata().alias());
        assertEquals("two", second.metadata().alias());
        assertEquals(2, store.list().size());
        assertEquals("one", store.resolveAlias(null));

        store.use("two");
        assertEquals("two", store.resolveAlias(null));
        store.updateMetadata(second.metadata().withEnabled(false).withWeight(7));
        assertFalse(store.load("two").metadata().enabled());
        assertEquals(7, store.load("two").metadata().weight());

        store.remove("two");
        assertEquals("one", store.resolveAlias(null));
    }

    @Test
    void protectsSecretFilesAndRejectsUnsafeAliases() throws Exception {
        AccountStore store = new AccountStore(temporaryDirectory.resolve("state"));
        StoredAccount saved = store.save("safe", token("one"), account("mail-without-at"), false);

        assertThrows(CliException.class, () -> store.save("../escape", token("x"), account("x@y.test"), false));
        if (Files.getFileStore(saved.directory()).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(saved.directory().resolve("token.json"));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        }
        assertTrue(Files.isRegularFile(saved.directory().resolve("account.json")));
    }

    private static OpenAiTokenResponse token(String value) {
        return new OpenAiTokenResponse(value, "refresh-" + value, "id-" + value,
                3600, 0, "openid", "Bearer");
    }

    private static OpenAiAccount account(String email) {
        return new OpenAiAccount(email, "account-id", "user-id", "plus", "org-id", Instant.parse("2030-01-01T00:00:00Z"));
    }
}
