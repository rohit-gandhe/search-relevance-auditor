package labs.augmentor.auditor.publish;

import com.fasterxml.jackson.databind.JsonNode;
import labs.augmentor.auditor.Json;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The `gh` CLI, shelled out to.
 *
 * Deliberately not a hand-rolled GitHub client: `gh` already holds the credential
 * in the system keyring, refreshes it, and is the thing a human would use to check
 * the result. One fewer secret in .env, and no token to regenerate.
 */
final class Gh {

    private Gh() {}

    record Result(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }

        JsonNode json() {
            try {
                return Json.mapper().readTree(stdout);
            } catch (Exception e) {
                throw new IllegalStateException("gh returned non-JSON: " + stdout, e);
            }
        }
    }

    static Result run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("gh");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            String out = drain(process.getInputStream());
            String err = drain(process.getErrorStream());
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("gh timed out: " + String.join(" ", command));
            }
            return new Result(process.exitValue(), out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running gh", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not run gh — is it installed and authenticated?", e);
        }
    }

    static Result require(String... args) {
        Result result = run(args);
        if (!result.ok()) {
            throw new IllegalStateException("gh " + String.join(" ", args) + " failed: " + result.stderr());
        }
        return result;
    }

    private static String drain(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8).strip();
        }
    }
}
