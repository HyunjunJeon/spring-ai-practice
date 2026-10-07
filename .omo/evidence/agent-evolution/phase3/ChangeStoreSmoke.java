import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.approval.ApprovalDecision;

class ChangeStoreSmoke {
	public static void main(String[] args) throws Exception {
		Path base = Path.of(args[1]).toRealPath();
		Path work = base.resolve("workspace");
		Path home = base.resolve("state");
		if (args[0].equals("create")) {
			Files.createDirectory(work);
			Files.writeString(work.resolve("source.txt"), "old\n");
			var policy = new PathPolicy(work, home, List.of());
			byte[] before = "old\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			byte[] after = "new\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			var old = new Fingerprinter(policy).fingerprint("source.txt");
			var next = new Fingerprint("source.txt", EntryKind.REGULAR_FILE, Fingerprinter.sha256(after),
					after.length, old.posixMode(), null);
			var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
			var at = Instant.now();
			var proposal = ChangeSet.propose(UUID.randomUUID(), owner, work.toRealPath().toString(),
					new ModelRef("fake", "smoke"), List.of(UUID.randomUUID()),
					Map.of("source.txt", old), Map.of("source.txt", next),
					List.of(new DiffRenderer(policy).render(old, before, next, after)), at);
			try (var lease = new FileChangeStore(home, work).acquire()) {
				lease.propose(proposal);
				lease.decide(proposal.changeSetId(), owner, proposal.digest(),
						new Approval(UUID.randomUUID(), proposal.changeSetId(), proposal.digest(), owner,
								"console", ApprovalDecision.APPROVED, at));
				lease.transition(proposal.changeSetId(), owner, proposal.digest(), ChangeStatus.APPLYING);
				lease.prepare(proposal.changeSetId(), owner, proposal.digest(), UUID.randomUUID(),
						"source.txt", before, after, at);
			}
			System.out.println("PREPARED_DURABLE");
		}
		else {
			try {
				var store = new FileChangeStore(home, work);
				try (var lease = store.acquire()) {
					var view = lease.read();
					var snapshot = view.changes().getFirst();
					var checkpoint = snapshot.checkpoints().getFirst();
					if (!view.recoveryRequired() || checkpoint.state() != Checkpoint.State.PREPARED
							|| !Arrays.equals(lease.readBlob(checkpoint.beforeBlob()), "old\n".getBytes())
							|| !Arrays.equals(lease.readBlob(checkpoint.afterBlob()), "new\n".getBytes())
							|| !Files.readString(work.resolve("source.txt")).equals("old\n"))
						throw new AssertionError("Restart must retain backup and leave live source unchanged");
					System.out.println("RESTART_PRESERVES_PREPARED_BACKUPS_AND_LIVE_SOURCE");
				}
			}
			finally {
				try (var paths = Files.walk(base)) {
					for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
				}
				System.out.println("TEMP_WORKSPACE_REMOVED");
			}
		}
	}
}
