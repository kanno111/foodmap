package com.foodmap.deploy.uploader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

final class ReleaseDelta {
    private static final Pattern RELEASE_PATH = Pattern.compile("/opt/foodmap/releases/[a-f0-9]{40}-[0-9]+-[0-9]+");
    private static final Duration BASIS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(75);
    private static final Duration PACKAGE_TIMEOUT = Duration.ofSeconds(20);

    private ReleaseDelta() {}

    static boolean tryUpload(Path source, String incoming, String digest, OpenSshClient ssh, PrintStream log)
            throws InterruptedException {
        if (!Files.isRegularFile(source.resolve("backend.jar"))
                || !Files.isRegularFile(source.resolve("frontend/index.html"))
                || !Files.isRegularFile(source.resolve("commit.txt"))) {
            return false;
        }
        String step = "find current release";
        long start = System.nanoTime();
        boolean attemptedTransfer = false;
        try {
            var current = ssh.remote(
                    "command -v rsync >/dev/null && test -L /opt/foodmap/current && readlink -f /opt/foodmap/current",
                    BASIS_TIMEOUT, OpenSshClient.Output.CAPTURE);
            String basis = current.stdout().strip();
            if (!current.success() || !RELEASE_PATH.matcher(basis).matches()) {
                log.println("Release delta unavailable; using resumable SCP.");
                return false;
            }
            step = "transfer changed release data";
            attemptedTransfer = true;
            var synced = ssh.syncRelease(source, basis, TRANSFER_TIMEOUT);
            OpenSshClient.requireSuccess(synced, step);
            log.print(synced.stdout());

            step = "rebuild and check release archive";
            // 使用与 Actions 相同的打包参数，并以 CI 原始整包哈希为唯一验收依据。
            String packageCommand = "cd '" + incoming + "' && ("
                    + "tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --format=gnu"
                    + " -cf - -C delta backend.jar frontend commit.txt | gzip -n"
                    + ") > .delta-release.tar.gz"
                    + " && printf '%s *%s\\n' '" + digest + "' '.delta-release.tar.gz' | sha256sum --check --status"
                    + " && mv -fT -- .delta-release.tar.gz release.tar.gz";
            var packed = ssh.remote("set -o pipefail; " + packageCommand, PACKAGE_TIMEOUT, OpenSshClient.Output.QUIET);
            OpenSshClient.requireSuccess(packed, step);
            log.printf("Release delta transferred and verified in %d ms.%n", ChunkUploader.elapsedMillis(start));
            return true;
        } catch (IOException error) {
            // 增量阶段只写入本次暂存目录。任何失败都可安全走原整包校验及续传。
            log.printf("Could not %s; using resumable SCP.%n", step);
            return false;
        } finally {
            if (attemptedTransfer) {
                try {
                    var cleanup = ssh.remote("cd '" + incoming + "' && rm -rf -- delta .delta-release.tar.gz",
                            BASIS_TIMEOUT, OpenSshClient.Output.QUIET);
                    OpenSshClient.requireSuccess(cleanup, "Clean delta staging files");
                } catch (IOException error) {
                    log.println("Could not clean delta staging files.");
                }
            }
        }
    }
}
