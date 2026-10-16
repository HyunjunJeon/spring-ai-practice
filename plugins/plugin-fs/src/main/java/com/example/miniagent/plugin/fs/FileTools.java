package com.example.miniagent.plugin.fs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.model.ToolContextKeys;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 선언형 도구: 메서드에 {@link Tool} 을 붙이면 Spring AI 가 이름, 설명, JSON 스키마를 만들어 준다.
 *
 * <ul>
 *   <li>파라미터 이름은 {@code -parameters} 컴파일 옵션으로 보존되어 스키마의 속성 이름이 된다.</li>
 *   <li>{@link ToolContext} 파라미터는 스키마에서 빠지고, 코어가 넣어 준 값(작업 디렉터리)이 주입된다.</li>
 *   <li>메서드가 RuntimeException 을 던지면 Spring AI 가 메시지를 모델에게 도구 결과로 돌려준다.
 *       모델은 그 오류를 보고 스스로 다시 시도할 수 있다. (checked 예외는 루프 밖으로 던져진다)</li>
 * </ul>
 *
 * <p>안전장치 네 가지.
 * <ul>
 *   <li>경로는 작업 디렉터리와 {@code allowedRoots} 안으로만 풀린다. 심볼릭 링크는 실제 위치로 판단한다.</li>
 *   <li>모든 파일은 같은 폴더의 임시 파일에 다 쓴 뒤 원자적으로 게시한다. POSIX 에서는 새 파일은 0600,
 *       기존 파일은 원래 권한(실행 비트 등)을 유지한다. 원자적 이동을 지원하지 않으면 실패한다.</li>
 *   <li>{@code maxReadBytes} 보다 큰 파일은 읽지 않는다. read_file 은 요청한 줄만 메모리에 올린다.</li>
 *   <li>{@link FileCheckpoints} 가 있으면 쓰기 전에 원본을 보관해 {@code /undo} 로 턴 단위로 되돌릴 수 있게 한다.</li>
 * </ul>
 */
public class FileTools {

	private static final int DEFAULT_LIMIT = 500;

	private static final int MAX_LIST_ENTRIES = 200;

	static final String SEARCH_DESCRIPTION = "Sorted relative paths; offset is 0-based (0..10000), limit 1..100 (default 50). "
			+ "At most 10000 visited entries (exceeding this fails), depth 16, paths 1024 chars, output payload 16000 chars. "
			+ "Never follows symlinks. Fixed case-insensitive name exclusions, NOT gitignore: "
			+ ".git, target, build, dist, out, node_modules, vendor, .gradle, .m2, .cache, .config, .idea, "
			+ ".mini-agent, .omo, .ssh, .aws, .codex, .claude, auth, authentication, credentials, keys, certs, "
			+ "certificates, runs, run-requests, tool-evidence, model-selections, changes, logs; "
			+ "names starting .env/id_rsa/id_ed25519, .credentials/credentials.json/secrets.json/.npmrc/.netrc/auth.json, "
			+ "and .key/.pem/.crt/.cer/.der/.p12/.pfx/.jks/.keystore suffixes. "
			+ "Exclusions apply even to explicit search roots. depthLimited/byteLimited/offsetLimited report incomplete scans; "
			+ "narrow path to continue. nextOffset pages results only while within the offset ceiling.";

	private final List<Path> allowedRoots;

	private final long maxReadBytes;

	private final @Nullable FileCheckpoints checkpoints;

	/**
	 * @param allowedRoots 작업 디렉터리 외에 접근을 허용할 디렉터리
	 * @param maxReadBytes 읽거나 편집할 수 있는 파일의 최대 크기
	 * @param checkpoints 쓰기 전 원본 보관소. null 이면 보관하지 않는다.
	 */
	public FileTools(List<Path> allowedRoots, long maxReadBytes, @Nullable FileCheckpoints checkpoints) {
		this.allowedRoots = List.copyOf(allowedRoots);
		this.maxReadBytes = maxReadBytes;
		this.checkpoints = checkpoints;
	}

	@Tool(name = "read_file", description = "Read a UTF-8 text file. Returns lines prefixed with line numbers. "
			+ "Use offset and limit to page through large files.")
	public String readFile(
			@ToolParam(description = "File path inside the working directory, absolute or relative to it") String path,
			@ToolParam(description = "1-based line number to start from", required = false) @Nullable Integer offset,
			@ToolParam(description = "Maximum number of lines to return (default 500)", required = false) @Nullable Integer limit,
			ToolContext toolContext) {
		Path file = resolve(path, toolContext);
		checkReadable(file);
		int from = Math.max(1, (offset != null) ? offset : 1);
		int count = Math.max(1, (limit != null) ? limit : DEFAULT_LIMIT);
		// 요청한 줄만 모은다. "남은 줄 수" 안내를 위해 끝까지 세기는 하지만 줄을 쌓아 두지는 않는다.
		List<String> page = new ArrayList<>();
		int total = 0;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				total++;
				if (total >= from && page.size() < count) {
					page.add("%6d  %s".formatted(total, line));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file + ": " + ex.getMessage(), ex);
		}
		if (page.isEmpty()) {
			// 비어 있는 페이지는 빈 파일이거나 offset 이 마지막 줄을 넘은 경우뿐이다. 끝을 넘은 offset 을 "빈 파일"로 알리면 모델이 파일을 덮어쓸 수 있다.
			return (total == 0) ? "(empty file)"
					: "Offset %d is out of range: %s has %d lines".formatted(from, file, total);
		}
		// from - 1 + count 는 int 를 넘을 수 있어 long 으로 계산한다. total 이 int 이므로 min 결과는 int 에 들어간다.
		int to = Math.toIntExact(Math.min(total, (long) from - 1 + count));
		String body = String.join("\n", page);
		if (to < total) {
			body += "\n... (%d more lines, call again with offset=%d)".formatted(total - to, to + 1);
		}
		return body;
	}

	@Tool(name = "write_file", description = "Create a file or overwrite it completely with the given content. "
			+ "Parent directories are created as needed.")
	public String writeFile(@ToolParam(description = "File path inside the working directory") String path,
			@ToolParam(description = "Full file content") String content, ToolContext toolContext) {
		Path file = resolveMutation(path, toolContext);
		if (Files.isDirectory(file)) {
			throw new IllegalArgumentException("Is a directory: " + file);
		}
		validateStageWrite(file, content, toolContext);
		checkpoint(file, toolContext);
		write(file, content);
		completedWrite(file, toolContext);
		recordStage(toolContext);
		return "Wrote %d chars to %s".formatted(content.length(), file);
	}

	@Tool(name = "edit_file", description = "Replace an exact snippet of text in a file. "
			+ "old_text must match exactly once (including whitespace).")
	public String editFile(@ToolParam(description = "File path inside the working directory") String path,
			@ToolParam(description = "Exact text to replace") String oldText,
			@ToolParam(description = "Replacement text") String newText, ToolContext toolContext) {
		Path file = resolveMutation(path, toolContext);
		checkReadable(file);
		// 원문 그대로 읽고 바꾼다. 줄 단위로 읽어 다시 합치면 CRLF 와 마지막 줄바꿈이 사라진다.
		String content = readString(file);
		// 모델은 보통 LF 로 보내므로, CRLF 파일이면 찾을 문자열과 바꿀 문자열도 CRLF 로 맞춘다.
		boolean crlf = content.contains("\r\n");
		String find = crlf ? toCrlf(oldText) : oldText;
		String replacement = crlf ? toCrlf(newText) : newText;
		int first = content.indexOf(find);
		if (first < 0) {
			throw new IllegalArgumentException("old_text not found in " + file);
		}
		if (content.indexOf(find, first + 1) >= 0) {
			throw new IllegalArgumentException("old_text occurs more than once in " + file + "; include more context");
		}
		String edited = content.replace(find, replacement);
		validateStageWrite(file, edited, toolContext);
		checkpoint(file, toolContext);
		write(file, edited);
		completedWrite(file, toolContext);
		recordStage(toolContext);
		return "Edited " + file;
	}

	@Tool(name = "list_dir", description = "List files and directories in a directory (non-recursive).")
	public String listDir(@ToolParam(description = "Directory path, '.' for the working directory") String path,
			ToolContext toolContext) {
		Path dir = resolve(path, toolContext);
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException("Not a directory: " + dir);
		}
		try (Stream<Path> entries = Files.list(dir)) {
			List<String> names = entries.sorted()
				.map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
				.toList();
			String listing = names.stream().limit(MAX_LIST_ENTRIES).collect(Collectors.joining("\n"));
			return (names.size() > MAX_LIST_ENTRIES)
					? listing + "\n... (%d more entries)".formatted(names.size() - MAX_LIST_ENTRIES) : listing;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot list " + dir + ": " + ex.getMessage(), ex);
		}
	}

	@Tool(name = "search_content", description = "Search UTF-8 files for a case-sensitive literal substring, one result per matching line. "
			+ "No regex. Skips invalid UTF-8/binary (non-whitespace control characters), files above min(max-read-bytes, 1MiB), "
			+ "and lines above 4096 chars; snippets are first 512 chars. Reads at most 16MiB per call. " + SEARCH_DESCRIPTION)
	public FileSearch.Page searchContent(
			@ToolParam(description = "Directory path, '.' for the working directory") String path,
			@ToolParam(description = "Nonempty literal substring, at most 256 chars, no line breaks") String query,
			@ToolParam(description = "0-based match offset, maximum 10000", required = false) @Nullable Integer offset,
			@ToolParam(description = "Page size 1..100, default 50", required = false) @Nullable Integer limit,
			ToolContext toolContext) {
		return new FileSearch(p -> resolve(p.toString(), toolContext), this.maxReadBytes)
			.search(searchRoot(path, toolContext), query, false, offset, limit);
	}

	@Tool(name = "glob_paths", description = "Find regular file paths using a slash-separated glob: * and ? match within "
			+ "one segment, ** as a whole segment matches zero or more directories (so **/*.java includes root files). "
			+ "No brackets, braces, escapes or regex. Does not read contents; binary/oversized files may be listed. " + SEARCH_DESCRIPTION)
	public FileSearch.Page globPaths(
			@ToolParam(description = "Directory path, '.' for the working directory") String path,
			@ToolParam(description = "Relative glob, at most 256 chars and 32 slash-separated segments") String pattern,
			@ToolParam(description = "0-based match offset, maximum 10000", required = false) @Nullable Integer offset,
			@ToolParam(description = "Page size 1..100, default 50", required = false) @Nullable Integer limit,
			ToolContext toolContext) {
		return new FileSearch(p -> resolve(p.toString(), toolContext), this.maxReadBytes)
			.search(searchRoot(path, toolContext), pattern, true, offset, limit);
	}

	private Path searchRoot(String path, ToolContext context) {
		Path root = resolve(path, context);
		Path base = Path.of(context.getContext().getOrDefault(ToolContextKeys.CWD, Path.of("").toAbsolutePath()).toString())
			.toAbsolutePath().normalize();
		Path input = base.resolve(path).normalize();
		Path stageRoot = StagedWorkspace.from(context).map(StagedWorkspace::root).orElse(null);
		// Reject explicit link roots too; normal traversal below never follows links.
		for (Path p = input; p != null && !p.equals(base)
				&& !this.allowedRoots.contains(p)
				&& !p.equals(stageRoot); p = p.getParent()) {
			if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("Search does not follow symlinks: " + path);
		}
		Path boundary = StagedWorkspace.from(context).map(StagedWorkspace::root).filter(root::startsWith)
			.orElseGet(() -> root.startsWith(realPath(base)) ? realPath(base)
				: this.allowedRoots.stream().map(FileTools::realPath).filter(root::startsWith).findFirst().orElse(root));
		for (Path part : boundary.relativize(root)) {
			if (FileSearch.ignored(part.toString())) throw new IllegalArgumentException("Ignored search root: " + path);
		}
		if (FileSearch.ignored(root.getFileName().toString())) throw new IllegalArgumentException("Ignored search root: " + path);
		return root;
	}

	private void checkReadable(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("File not found: " + file);
		}
		long size;
		try {
			size = Files.size(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file + ": " + ex.getMessage(), ex);
		}
		if (size > this.maxReadBytes) {
			throw new IllegalArgumentException(("File too large: %s is %d bytes (limit %d, agent.plugins.fs.max-read-bytes). "
					+ "Read part of it with bash (head, sed -n) instead.").formatted(file, size, this.maxReadBytes));
		}
	}

	private static String readString(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file + ": " + ex.getMessage(), ex);
		}
	}

	private static String toCrlf(String text) {
		return text.replace("\r\n", "\n").replace("\n", "\r\n");
	}

	/** 이 턴에 처음 바꾸는 파일이면 원본을 보관한다. 턴 ID 는 코어가 ToolContext 에 넣어 준다. */
	private void checkpoint(Path file, ToolContext toolContext) {
		if (this.checkpoints == null || StagedWorkspace.from(toolContext).isPresent()) {
			return;
		}
		Map<String, Object> context = toolContext.getContext();
		this.checkpoints.beforeWrite(String.valueOf(context.getOrDefault(ToolContextKeys.SESSION_ID, "default")),
				String.valueOf(context.getOrDefault(ToolContextKeys.TURN_ID, "default")), file);
	}

	private void completedWrite(Path file, ToolContext toolContext) {
		if (this.checkpoints == null || StagedWorkspace.from(toolContext).isPresent()) {
			return;
		}
		Map<String, Object> context = toolContext.getContext();
		this.checkpoints.afterWrite(String.valueOf(context.getOrDefault(ToolContextKeys.SESSION_ID, "default")),
				String.valueOf(context.getOrDefault(ToolContextKeys.TURN_ID, "default")), file);
	}

	/** 임시 파일을 완성한 뒤 원자적으로 게시한다. 디스크 동기화나 동시 쓰기 조정은 보장하지 않는다. */
	private static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".tmp");
			Throwable failure = null;
			try {
				Files.writeString(temp, content, StandardCharsets.UTF_8);
				if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
					Files.setPosixFilePermissions(temp, Files.exists(file) ? Files.getPosixFilePermissions(file)
							: PosixFilePermissions.fromString("rw-------"));
				}
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException | RuntimeException | Error ex) {
				failure = ex;
				throw ex;
			}
			finally {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException | RuntimeException | Error ex) {
					if (failure != null) {
						failure.addSuppressed(ex);
					}
					else {
						throw ex;
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot write " + file + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * 작업 디렉터리 기준으로 경로를 풀고, 실제 위치(심볼릭 링크를 따라간 위치)가 허용된 디렉터리 안인지 확인한다.
	 * 실제 위치를 돌려주므로 링크를 거쳐 편집해도 링크가 가리키는 파일이 바뀌고, 링크 자체는 그대로 남는다.
	 */
	private Path resolve(String path, ToolContext toolContext) {
		var stage = StagedWorkspace.from(toolContext);
		if (stage.isPresent()) {
			try { return stage.get().resolve(path, false, this.allowedRoots); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
		Object cwd = toolContext.getContext().get(ToolContextKeys.CWD);
		Path base = (cwd != null) ? Path.of(cwd.toString()) : Path.of("").toAbsolutePath();
		Path target = realPath(base.resolve(path).normalize());
		List<Path> roots = new ArrayList<>();
		roots.add(base);
		roots.addAll(this.allowedRoots);
		if (roots.stream().map(FileTools::realPath).noneMatch(target::startsWith)) {
			throw new IllegalArgumentException(("Access denied: %s is outside the allowed directories %s. "
					+ "The user can allow more with agent.plugins.fs.allowed-roots.").formatted(target, roots));
		}
		return target;
	}

	private Path resolveMutation(String path, ToolContext context) {
		var stage = StagedWorkspace.from(context);
		if (stage.isEmpty()) return resolve(path, context);
		try { return stage.get().resolve(path, true, this.allowedRoots); }
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}

	private static void recordStage(ToolContext context) {
		var stage = StagedWorkspace.from(context);
		if (stage.isPresent()) {
			try { stage.get().record(); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
	}

	private static void validateStageWrite(Path file, String content, ToolContext context) {
		var stage = StagedWorkspace.from(context);
		if (stage.isPresent()) {
			try { stage.get().validateWrite(file, content); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
	}

	/** 존재하는 가장 가까운 상위 경로까지 실제 위치로 바꾸고, 아직 없는 나머지 이름을 그 뒤에 붙인다. */
	private static Path realPath(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path existing = absolute;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return absolute;
		}
		try {
			return existing.toRealPath().resolve(existing.relativize(absolute));
		}
		catch (IOException ex) {
			// 가리키는 대상이 없는 링크처럼 실제 위치를 알 수 없으면 허용하지 않는다.
			throw new IllegalArgumentException("Cannot resolve " + path + ": " + ex.getMessage(), ex);
		}
	}

}
