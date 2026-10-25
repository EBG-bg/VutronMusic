package io.github.vutronmusic.personal;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class AppUpdatePolicyTest {
    @Test public void onlyPublicEditionMayDownloadOrInstallPublicUpdates() {
        assertTrue(AppUpdateManager.allowsInstall("mobile"));
        assertFalse(AppUpdateManager.allowsInstall("personal"));
        assertFalse(AppUpdateManager.allowsInstall(""));
        assertFalse(AppUpdateManager.allowsInstall(null));
    }
    @Test public void localModeAllowsOnlyExplicitManualChecks() {
        assertFalse(AppUpdateManager.allowsCheck(false, true));
        assertTrue(AppUpdateManager.allowsCheck(true, true));
        assertTrue(AppUpdateManager.allowsCheck(false, false));
        assertTrue(AppUpdateManager.allowsCheck(true, false));
    }
    @Test public void defaultAndDisabledNeverAutomaticallyCheck() {
        assertFalse(AppUpdateManager.shouldCheck(false, false, 0, 100, false));
        assertFalse(AppUpdateManager.shouldCheck(false, false, 1, Long.MAX_VALUE, false));
        assertTrue(AppUpdateManager.shouldCheck(true, false, 99, 100, false));
    }
    @Test public void automaticChecksRespectIntervalAndBusyState() {
        long interval = AppUpdateManager.AUTO_INTERVAL_MS;
        assertTrue(AppUpdateManager.shouldCheck(false, true, 0, 100, false));
        assertFalse(AppUpdateManager.shouldCheck(false, true, 100, 100 + interval - 1, false));
        assertTrue(AppUpdateManager.shouldCheck(false, true, 100, 100 + interval, false));
        assertFalse(AppUpdateManager.shouldCheck(true, true, 0, 100, true));
        assertTrue(AppUpdateManager.shouldCheck(false, true, 200, 100, false));
    }
    @Test public void signerSetsMustMatchAndCannotBeEmpty() {
        assertTrue(AppUpdateManager.sameSignerDigests(new String[]{"a", "b"}, new String[]{"b", "a"}));
        assertFalse(AppUpdateManager.sameSignerDigests(new String[]{"a"}, new String[]{"b"}));
        assertFalse(AppUpdateManager.sameSignerDigests(new String[0], new String[0]));
        assertFalse(AppUpdateManager.sameSignerDigests(new String[]{""}, new String[]{""}));
        assertFalse(AppUpdateManager.sameSignerDigests(new String[]{"a"}, null));
    }
    @Test public void downloadedBytesMustMatchBothSizeAndSha256() throws Exception {
        File file = File.createTempFile("mobile-update-test", ".apk");
        try {
            byte[] bytes = new byte[]{0, 1, 2, 3};
            Files.write(file.toPath(), bytes);
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b & 255));
            AppUpdateManager.verifyDownloadedFile(file, sha.toString(), bytes.length);
            assertThrows(java.io.IOException.class, () -> AppUpdateManager.verifyDownloadedFile(file, sha.toString(), 5));
            assertThrows(java.io.IOException.class, () -> AppUpdateManager.verifyDownloadedFile(file, "0".repeat(64), 4));
            assertThrows(java.io.IOException.class, () -> AppUpdateManager.verifyDownloadedFile(file, "bad", 4));
            Files.write(file.toPath(), new byte[]{0, 1, 2, 4});
            assertThrows(java.io.IOException.class, () -> AppUpdateManager.verifyDownloadedFile(file, sha.toString(), 4));
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
