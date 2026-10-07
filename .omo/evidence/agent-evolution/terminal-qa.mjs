import {readFile, mkdir} from "node:fs/promises";
import {resolve} from "node:path";

const root = resolve(import.meta.dir, "../../..");
const evidence = import.meta.dir;
const home = resolve(evidence, "F4-home-live");
const workspace = resolve(evidence, "F4-work");
await mkdir(home, {recursive: true});
const html = await readFile(resolve(evidence, "terminal-qa.html"), "utf8");
const js = await (await fetch("https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.js")).text();
const css = await (await fetch("https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.css")).text();
const raw = [];
const decoder = new TextDecoder();
let process;
const server = Bun.serve({
  hostname: "127.0.0.1", port: 0,
  async fetch(request, server) {
    const path = new URL(request.url).pathname;
    if (path === "/stream") {
      if (server.upgrade(request)) return;
      return new Response("Upgrade required", {status: 400});
    }
    if (path === "/input" && request.method === "POST") {
      const body = await request.json();
      process.terminal.write(body.input);
      return new Response("OK");
    }
    if (path === "/resize" && request.method === "POST") {
      const body = await request.json();
      process.terminal.resize(body.cols, body.rows);
      return new Response("OK");
    }
    return new Response(path === "/xterm.js" ? js : path === "/xterm.css" ? css : html, {
      headers: {"content-type": path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css" : "text/html"}
    });
  },
  websocket: {
    open(socket) { socket.subscribe("terminal"); if (raw.length) socket.send(raw.join("")); },
    message(socket, data) { process?.terminal.write(String(data)); }
  }
});
process = Bun.spawn([
  "/opt/homebrew/opt/openjdk@21/bin/java", "-jar",
  resolve(root, "mini-agent/agent-app/target/mini-agent.jar"),
  "--agent.ui=tui", "--agent.home=" + home, "--agent.cwd=" + workspace,
  "--agent.plugins.memory.enabled=false",
  "--agent.plugins.permission.auto-allow-tools=list_dir,load_skill"
], {
  cwd: root, env: {...globalThis.process.env, TERM: "xterm-256color", COLORTERM: "truecolor"},
  terminal: {
    cols: 120, rows: 32,
    data(terminal, data) {
      const text = decoder.decode(data, {stream: true});
      raw.push(text); server.publish("terminal", text);
    }
  }
});
console.log("READY " + server.url);
try {
  const code = await process.exited;
  console.log("JAVA_EXIT " + code);
} finally {
  process.terminal?.close();
  server.stop(true);
}
