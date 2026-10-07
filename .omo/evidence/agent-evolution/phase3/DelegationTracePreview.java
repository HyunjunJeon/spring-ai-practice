import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.plugin.tui.TraceGraph;

/** Render fixture only, not a claim of runtime integration or terminal screenshot QA. */
public class DelegationTracePreview {
	public static void main(String[] args) {
		for (int width : new int[] {18, 38, 80}) {
			var graph = new TraceGraph();
			graph.accept(new TraceEvent.RunStateChanged("parent-run", "parent", "attempt",
					RunState.RUNNING, new ModelRef("fake", "parent")));
			graph.accept(new TraceEvent.DelegationStateChanged("parent", "한글작업", "research", "child-run",
					new ModelRef("fake", "child"), ChildStatus.RUNNING, RunState.WAITING_APPROVAL, null, null));
			graph.accept(new TraceEvent.DelegationStateChanged("parent", "한글작업", "verify", "verify-run",
					new ModelRef("fake", "verifier"), ChildStatus.FAILED, RunState.COMPLETED, null, "STALE"));
			graph.accept(new TraceEvent.DelegationStateChanged("parent", "한글작업", null, null,
					null, null, null, DelegationResult.Status.PARTIAL, null));
			System.out.println("WIDTH=" + width + " CANCEL_OWNER=" + graph.activeRunId());
			graph.render(20, width).forEach(line -> System.out.println(line.plain()));
		}
	}
}
