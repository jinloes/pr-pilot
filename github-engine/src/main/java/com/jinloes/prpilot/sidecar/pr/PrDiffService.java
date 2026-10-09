package com.jinloes.prpilot.sidecar.pr;

import com.jinloes.prpilot.model.DiffCoverage;
import com.jinloes.prpilot.sidecar.github.GitHubApiBase;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubHttpClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

/**
 * Retrieves a review diff bounded to whole files, naming anything omitted, without exposing GitHub
 * credentials outside the sidecar.
 */
public final class PrDiffService {
    static final int REVIEW_LIMIT_BYTES = 250_000;
    static final int VALIDATION_LIMIT_BYTES = 1_000_000;
    static final int PER_FILE_CAP_BYTES = 250_000;
    static final long SCAN_CEILING_BYTES = 64L * 1024 * 1024;
    static final int TRAILER_RESERVE_BYTES = 16_384;
    private static final String SECTION_START = "diff --git ";
    private static final int PATH_SCAN_BYTES = 64 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private final GitHubAuthService.TokenResolver tokenResolver;
    private final DiffClient diffClient;
    private final CompareClient compareClient;
    private final Backoff backoff;

    public PrDiffService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                new HttpDiffClient(),
                new ThreadBackoff());
    }

    PrDiffService(GitHubAuthService.TokenResolver tokenResolver, DiffClient diffClient) {
        this(tokenResolver, diffClient, new ThreadBackoff());
    }

    PrDiffService(
            GitHubAuthService.TokenResolver tokenResolver, DiffClient diffClient, Backoff backoff) {
        this(tokenResolver, diffClient, new HttpDiffClient(), backoff);
    }

    PrDiffService(
            GitHubAuthService.TokenResolver tokenResolver,
            DiffClient diffClient,
            CompareClient compareClient,
            Backoff backoff) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.diffClient = Objects.requireNonNull(diffClient);
        this.compareClient = Objects.requireNonNull(compareClient);
        this.backoff = Objects.requireNonNull(backoff);
    }

    GitHubAuthService.TokenResolution resolveToken(String hostnameArgument) {
        return tokenResolver.resolve(hostnameArgument);
    }

    /**
     * Fetches {@code baseSha...headSha} as a review-bounded diff with the same retry policy as
     * {@link #get}, keeping the HTTP code so callers can map compare-specific failures.
     */
    CompareResponse compare(
            String apiBaseUrl,
            String token,
            String owner,
            String repo,
            String baseSha,
            String headSha) {
        CompareResponse response = new CompareResponse(Response.of(Status.NETWORK), 0);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response =
                    compareClient.compare(
                            apiBaseUrl, token, owner, repo, baseSha, headSha, REVIEW_LIMIT_BYTES);
            if (!retryable(response.response().status())
                    || attempt == MAX_ATTEMPTS
                    || Thread.currentThread().isInterrupted()) break;
            backoff.pause(attempt);
        }
        return response;
    }

    public PrDiffResult get(Params params) {
        if (params.number() <= 0
                || !valid(params.owner())
                || !valid(params.repo())
                || !("review".equals(params.mode()) || "validation".equals(params.mode()))) {
            return PrDiffResult.failure("invalid_request", "Pull request diff request is invalid.");
        }
        GitHubApiBase base = GitHubApiBase.parse(params.githubBaseUrl());
        if (base == null)
            return PrDiffResult.failure(
                    "invalid_base_url", "GitHub base URL must be an HTTPS origin.");
        GitHubAuthService.TokenResolution token = tokenResolver.resolve(base.hostnameArgument());
        if (token.status() == GitHubAuthService.TokenStatus.NOT_INSTALLED)
            return PrDiffResult.failure("not_installed", "GitHub CLI is not installed.");
        if (token.status() != GitHubAuthService.TokenStatus.RESOLVED)
            return PrDiffResult.failure(
                    "not_authenticated", "Run 'gh auth login' in a terminal for this GitHub host.");
        int limitBytes =
                "validation".equals(params.mode()) ? VALIDATION_LIMIT_BYTES : REVIEW_LIMIT_BYTES;
        Response response = Response.of(Status.NETWORK);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response =
                    diffClient.get(
                            base.apiBaseUrl(),
                            token.token(),
                            params.owner(),
                            params.repo(),
                            params.number(),
                            limitBytes);
            if (!retryable(response.status())
                    || attempt == MAX_ATTEMPTS
                    || Thread.currentThread().isInterrupted()) break;
            backoff.pause(attempt);
        }
        return switch (response.status()) {
            case OK -> PrDiffResult.success(response.diff(), response.truncated(), limitBytes);
            case UNAUTHENTICATED ->
                    PrDiffResult.failure(
                            "not_authenticated",
                            "Run 'gh auth login' in a terminal for this GitHub host.");
            case RATE_LIMITED ->
                    PrDiffResult.failure(
                            "rate_limited", "GitHub rate limit exceeded. Try again shortly.");
            case NETWORK ->
                    PrDiffResult.failure(
                            "network_error", "Unable to reach GitHub. Check your connection.");
            case NOT_FOUND ->
                    PrDiffResult.failure(
                            PrDiffResult.STATUS_NOT_FOUND_OR_INACCESSIBLE,
                            "Pull request not found or inaccessible to the active gh account.");
            case API, TRANSIENT_API ->
                    PrDiffResult.failure("api_failed", "GitHub API request failed.");
            case TOO_LARGE ->
                    PrDiffResult.failure(
                            PrDiffResult.STATUS_DIFF_TOO_LARGE,
                            "GitHub declined to return this pull request's diff (HTTP 406); it"
                                    + " likely exceeds GitHub's diff size limits.");
        };
    }

    public record Params(
            String githubBaseUrl, String owner, String repo, int number, String mode) {}

    interface DiffClient {
        Response get(
                String api, String token, String owner, String repo, int number, int limitBytes);
    }

    interface CompareClient {
        CompareResponse compare(
                String api,
                String token,
                String owner,
                String repo,
                String baseSha,
                String headSha,
                int limitBytes);
    }

    /** A bounded compare diff plus the HTTP status code; {@code 0} means no HTTP response. */
    record CompareResponse(Response response, int statusCode) {}

    interface Backoff {
        void pause(int attempt);
    }

    record Response(Status status, String diff, boolean truncated) {
        static Response ok(String diff, boolean truncated) {
            return new Response(Status.OK, diff, truncated);
        }

        static Response of(Status status) {
            return new Response(status, null, false);
        }
    }

    enum Status {
        OK,
        UNAUTHENTICATED,
        RATE_LIMITED,
        NETWORK,
        TRANSIENT_API,
        NOT_FOUND,
        TOO_LARGE,
        API
    }

    static final class HttpDiffClient implements DiffClient, CompareClient {
        private final GitHubHttpClient httpClient = new GitHubHttpClient();

        @Override
        public CompareResponse compare(
                String api,
                String token,
                String owner,
                String repo,
                String baseSha,
                String headSha,
                int limitBytes) {
            String url =
                    api + "/repos/" + owner + "/" + repo + "/compare/" + baseSha + "..." + headSha;
            try {
                return httpClient.stream(
                        url,
                        token,
                        GitHubHttpClient.ACCEPT_DIFF,
                        (statusCode, body) -> {
                            if (statusCode < 200 || statusCode >= 300) {
                                return new CompareResponse(
                                        Response.of(classifyFailure(statusCode)), statusCode);
                            }
                            return new CompareResponse(
                                    bound(body, limitBytes, PER_FILE_CAP_BYTES, SCAN_CEILING_BYTES),
                                    statusCode);
                        });
            } catch (IOException e) {
                return new CompareResponse(Response.of(Status.NETWORK), 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CompareResponse(Response.of(Status.NETWORK), 0);
            }
        }

        @Override
        public Response get(
                String api, String token, String owner, String repo, int number, int limitBytes) {
            String url = api + "/repos/" + owner + "/" + repo + "/pulls/" + number;
            try {
                return httpClient.stream(
                        url,
                        token,
                        GitHubHttpClient.ACCEPT_DIFF,
                        (statusCode, body) -> {
                            if (statusCode < 200 || statusCode >= 300) {
                                return Response.of(classifyFailure(statusCode));
                            }
                            return bound(body, limitBytes, PER_FILE_CAP_BYTES, SCAN_CEILING_BYTES);
                        });
            } catch (IOException e) {
                return Response.of(Status.NETWORK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Response.of(Status.NETWORK);
            }
        }
    }

    /**
     * Bounds a locally rendered unified diff exactly as a review-mode fetch would, so offline tools
     * such as the review benchmark hand the model the same diff shape the hosts do.
     */
    public static PrDiffResult boundReviewDiff(InputStream input) throws IOException {
        Response response =
                bound(input, REVIEW_LIMIT_BYTES, PER_FILE_CAP_BYTES, SCAN_CEILING_BYTES);
        return PrDiffResult.success(response.diff(), response.truncated(), REVIEW_LIMIT_BYTES);
    }

    /**
     * Bounds a locally rendered unified diff exactly as a validation-mode fetch would. Hosts send
     * this larger diff to chunked reviews, so offline tools use it to mirror chunked mode.
     */
    public static PrDiffResult boundValidationDiff(InputStream input) throws IOException {
        Response response =
                bound(input, VALIDATION_LIMIT_BYTES, PER_FILE_CAP_BYTES, SCAN_CEILING_BYTES);
        return PrDiffResult.success(response.diff(), response.truncated(), VALIDATION_LIMIT_BYTES);
    }

    /**
     * Streams a unified diff and keeps whole file sections only, so a bounded diff never splits a
     * UTF-8 sequence or a hunk. Sections over {@code perFileCap} are dropped as they stream; while
     * the kept total exceeds {@code limitBytes} the largest kept section is evicted (ties: the
     * later one), which keeps the maximal smallest-first set and makes a smaller limit's selection
     * a subset of a larger one's. Anything omitted is named in a {@link DiffCoverage} trailer that
     * fits a reserve inside the limit. A complete diff comes back byte-identical, with no trailer.
     */
    static Response bound(InputStream input, int limitBytes, int perFileCap, long scanCeiling)
            throws IOException {
        DiffSections sections = new DiffSections(perFileCap, limitBytes);
        byte[] buffer = new byte[8192];
        long remaining = scanCeiling;
        boolean scanComplete = true;
        while (true) {
            if (remaining <= 0) {
                scanComplete = input.read() < 0;
                break;
            }
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            remaining -= read;
            for (int index = 0; index < read; index++) sections.accept(buffer[index]);
        }
        return sections.finish(limitBytes, scanComplete);
    }

    /**
     * The changed path a file section names: {@code +++ b/}, else {@code --- a/}, else the last
     * {@code b/} in its {@code diff --git} header. C-quoted paths stay quoted and escaped. Only the
     * header area before the first hunk is read, so an added line cannot impersonate a header.
     */
    static String pathOf(byte[] section, int length) {
        String head =
                new String(section, 0, Math.min(length, PATH_SCAN_BYTES), StandardCharsets.UTF_8);
        String[] lines = head.split("\n", -1);
        int headerLine = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].startsWith(SECTION_START)) {
                headerLine = index;
                break;
            }
        }
        if (headerLine < 0) return "";
        String minus = null;
        String plus = null;
        for (int index = headerLine + 1; index < lines.length; index++) {
            if (lines[index].startsWith("@@")) break;
            String line = trimLineEnd(lines[index]);
            if (plus == null) plus = headerPath(line, "+++ ", 'b');
            if (minus == null) minus = headerPath(line, "--- ", 'a');
        }
        if (plus != null) return plus;
        if (minus != null) return minus;
        String header = trimLineEnd(lines[headerLine]);
        int plain = header.lastIndexOf(" b/");
        int quoted = header.lastIndexOf(" \"b/");
        if (quoted > plain) return "\"" + header.substring(quoted + 4);
        return plain >= 0 ? header.substring(plain + 3) : "";
    }

    private static String headerPath(String line, String marker, char side) {
        String plain = marker + side + "/";
        String quoted = marker + "\"" + side + "/";
        String path = null;
        if (line.startsWith(plain)) path = line.substring(plain.length());
        else if (line.startsWith(quoted)) path = "\"" + line.substring(quoted.length());
        return path == null || path.isEmpty() ? null : path;
    }

    private static String trimLineEnd(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\r' || line.charAt(end - 1) == '\t')) end--;
        return line.substring(0, end);
    }

    /** Incremental splitter and smallest-first selector for {@link #bound}. */
    private static final class DiffSections {
        private static final byte[] START = SECTION_START.getBytes(StandardCharsets.US_ASCII);

        private final int perFileCap;
        private final int limitBytes;
        private final PriorityQueue<Section> kept =
                new PriorityQueue<>(
                        Comparator.comparingInt((Section section) -> section.bytes().length)
                                .thenComparingInt(Section::index)
                                .reversed());
        private final List<Omission> omitted = new ArrayList<>();
        private long keptBytes;
        private int index;
        private byte[] current = new byte[1024];
        private int currentLength;
        private long currentSize;
        private boolean dropping;
        private boolean hasHeader;
        // Bytes of START matched from the current line start; -1 once the line cannot match.
        private int matched;

        DiffSections(int perFileCap, int limitBytes) {
            this.perFileCap = perFileCap;
            this.limitBytes = limitBytes;
        }

        void accept(byte value) {
            if (matched >= 0) {
                if (value == START[matched]) {
                    if (++matched == START.length) {
                        matched = -1;
                        // A header only ends the current section once it has a header of its
                        // own, so any preamble joins the first section.
                        if (hasHeader) finishSection();
                        appendStart(START.length);
                        hasHeader = true;
                    }
                    return;
                }
                appendStart(matched);
                matched = -1;
            }
            append(value);
            if (value == '\n') matched = 0;
        }

        Response finish(int limit, boolean scanComplete) {
            if (matched > 0) appendStart(matched);
            if (currentSize > 0) {
                if (scanComplete) finishSection();
                else if (!dropping) omit(index, pathOf(current, currentLength));
            }
            List<Section> body = new ArrayList<>(kept);
            body.sort(Comparator.comparingInt(Section::index));
            if (omitted.isEmpty() && scanComplete) {
                return Response.ok(new String(concat(body, false), StandardCharsets.UTF_8), false);
            }
            evictTo(Math.max(0, limit - TRAILER_RESERVE_BYTES));
            body = new ArrayList<>(kept);
            body.sort(Comparator.comparingInt(Section::index));
            byte[] bytes = concat(body, true);
            omitted.sort(Comparator.comparingInt(Omission::index));
            DiffCoverage coverage =
                    DiffCoverage.fitted(
                            omitted.size(),
                            omitted.stream().map(Omission::path).toList(),
                            limit,
                            scanComplete,
                            Math.min(TRAILER_RESERVE_BYTES, limit - bytes.length));
            return Response.ok(
                    new String(bytes, StandardCharsets.UTF_8) + coverage.trailer(),
                    !coverage.complete());
        }

        private void appendStart(int count) {
            for (int offset = 0; offset < count; offset++) append(START[offset]);
        }

        private void append(byte value) {
            currentSize++;
            if (dropping) return;
            if (currentLength == current.length) {
                current = Arrays.copyOf(current, current.length * 2);
            }
            current[currentLength++] = value;
            if (currentLength > perFileCap) {
                omit(index, pathOf(current, currentLength));
                dropping = true;
                current = new byte[0];
                currentLength = 0;
            }
        }

        private void finishSection() {
            if (!dropping && currentSize > 0) {
                kept.add(new Section(index, Arrays.copyOf(current, currentLength)));
                keptBytes += currentLength;
                evictTo(limitBytes);
            }
            index++;
            current = new byte[1024];
            currentLength = 0;
            currentSize = 0;
            dropping = false;
            hasHeader = false;
        }

        private void evictTo(long maxBytes) {
            while (keptBytes > maxBytes) {
                Section largest = kept.remove();
                keptBytes -= largest.bytes().length;
                omit(largest.index(), pathOf(largest.bytes(), largest.bytes().length));
            }
        }

        private void omit(int sectionIndex, String path) {
            omitted.add(new Omission(sectionIndex, path));
        }

        private static byte[] concat(List<Section> sections, boolean endWithNewline) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Section section : sections) out.writeBytes(section.bytes());
            byte[] bytes = out.toByteArray();
            if (endWithNewline && bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                bytes = Arrays.copyOf(bytes, bytes.length + 1);
                bytes[bytes.length - 1] = '\n';
            }
            return bytes;
        }
    }

    private record Section(int index, byte[] bytes) {}

    private record Omission(int index, String path) {}

    private static final class ThreadBackoff implements Backoff {
        @Override
        public void pause(int attempt) {
            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static Status classifyFailure(int statusCode) {
        if (statusCode == 401 || statusCode == 403) return Status.UNAUTHENTICATED;
        if (statusCode == 429) return Status.RATE_LIMITED;
        if (statusCode == 404) return Status.NOT_FOUND;
        if (statusCode == 406) return Status.TOO_LARGE;
        return statusCode >= 500 ? Status.TRANSIENT_API : Status.API;
    }

    private static boolean retryable(Status status) {
        return status == Status.RATE_LIMITED
                || status == Status.NETWORK
                || status == Status.TRANSIENT_API;
    }

    static boolean valid(String value) {
        return value != null && SEGMENT.matcher(value).matches();
    }
}
