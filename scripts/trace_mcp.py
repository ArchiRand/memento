#!/usr/bin/env python3
"""Trace of the memento MCP server over stdio.

Starts plugin/memento.jar like Claude Code does, runs a scripted session against a throwaway vault and git project,
prints every JSON-RPC request and response, checks expectations. Exit code 0 means every check passed.
Usage: python3 -I scripts/trace_mcp.py
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

JAR = pathlib.Path(__file__).resolve().parent.parent / "plugin" / "memento.jar"
TOOLS = {"read_context", "read_documents", "write_document", "edit_document",
         "append_content", "search_by_tags", "delete_document", "history"}
results = []


def check(condition, what):
    print(("PASS  " if condition else "FAIL  ") + what)
    results.append((bool(condition), what))


class Server:
    """One memento MCP process; name tells processes apart in the trace."""

    def __init__(self, name, cwd, env, log_dir):
        self.name = name
        self.stderr = open(log_dir / f"{name}.stderr.log", "w")
        self.proc = subprocess.Popen(["java", "-jar", str(JAR)], cwd=cwd, env=env, text=True, encoding="utf-8",
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr)
        self.next_id = 1

    def send(self, method, params=None, notify=False):
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        if not notify:
            message["id"] = self.next_id
            self.next_id += 1
        line = json.dumps(message, ensure_ascii=False)
        print(f"[{self.name}] >>> {line}")
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()
        if notify:
            return None
        while True:
            raw = self.proc.stdout.readline()
            if not raw:
                sys.exit(f"[{self.name}] server closed stdout, see {self.stderr.name}")
            response = json.loads(raw)
            print(f"[{self.name}] <<< {raw.strip()}")
            if response.get("id") == message["id"]:
                return response

    def initialize(self):
        response = self.send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                            "clientInfo": {"name": "memento-trace", "version": "1"}})
        self.send("notifications/initialized", notify=True)
        return response["result"]

    def call(self, tool, arguments):
        """Returns (is_error, parsed JSON on success or the error text)."""
        result = self.send("tools/call", {"name": tool, "arguments": arguments})["result"]
        text = result["content"][0]["text"]
        return (True, text) if result.get("isError") else (False, json.loads(text))

    def close(self):
        self.proc.stdin.close()
        self.proc.wait(timeout=10)
        self.stderr.close()


def git(directory, *args):
    return subprocess.run(["git", "-C", str(directory), *args], check=True, capture_output=True, text=True).stdout.strip()
def main():
    if not JAR.exists():
        sys.exit(f"{JAR} not found: run mvn package in server/ first")
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="memento-trace-")).resolve()
    vault, project = tmp / "vault", tmp / "trace-project"
    project.mkdir()
    git(project, "init", "-q")
    git(project, "remote", "add", "origin", "git@github.com:someone/trace-project.git")
    # windows-1252 is the default charset of Java 17 on Windows when stdout is a pipe: catches output in a non-UTF-8 charset
    env = dict(os.environ, MEMENTO_HOME=str(vault), JAVA_TOOL_OPTIONS="-Dfile.encoding=windows-1252")
    print(f"trace dir: {tmp}\n")

    first = Server("s1", project, env, tmp)
    info = first.initialize()
    check("edit_document" in info.get("instructions", ""), "initialize returns the server instructions")
    tools = {tool["name"] for tool in first.send("tools/list")["result"]["tools"]}
    check(tools == TOOLS, f"tools/list exposes exactly the 8 memento tools: {sorted(tools)}")

    error, context = first.call("read_context", {})
    check(not error and context["project"] == "trace-project" and context["documents"] == [],
          "an empty project is resolved from cwd through the origin name")

    error, written = first.call("write_document", {"name": "context.md", "content": "# Context\n\nline one\nПамять проекта\n",
                                                   "tags": ["context", "trace"], "core": True})
    revision = written["revision"] if not error else ""
    check(not error and len(revision) == 12, "write_document returns a 12-char revision")

    error, read = first.call("read_documents", {"names": ["context.md", "missing.md", "../bad.md"]})
    check(not error and read["documents"][0]["content"] == "# Context\n\nline one\nПамять проекта\n" and read["missing"] == ["missing.md", "../bad.md"],
          "read_documents returns content as written and reports missing and invalid names without failing")

    second = Server("s2", project, env, tmp)
    second.initialize()
    error, edited = second.call("edit_document", {"name": "context.md", "expectedRevision": revision,
                                                  "edits": [{"oldText": "line one", "newText": "line two"}]})
    check(not error and edited["revision"] != revision, "another process edits with the current revision")
    error, message = first.call("edit_document", {"name": "context.md", "expectedRevision": revision,
                                                  "edits": [{"oldText": "# Context", "newText": "# Lost"}]})
    check(error and "revision mismatch" in message, "a stale revision from the first process is rejected, no lost update")
    second.close()

    error, message = first.call("edit_document", {"name": "context.md", "edits": [{"oldText": "nope", "newText": "x"}]})
    check(error and "not found" in message, "edit with absent text is rejected")

    first.call("append_content", {"name": "progress.md", "content": "- step 1", "tags": ["progress", "trace"]})
    first.call("append_content", {"name": "progress.md", "content": "- step 2"})
    error, read = first.call("read_documents", {"names": ["progress.md"]})
    check(not error and read["documents"][0]["content"] == "- step 1\n\n- step 2",
          "append_content creates the document, then appends after a blank line")

    error, found = first.call("search_by_tags", {"tags": ["trace", "context"]})
    check(not error and [doc["name"] for doc in found["documents"]] == ["context.md"], "search_by_tags needs all tags")

    first.call("delete_document", {"name": "progress.md"})
    error, history = first.call("history", {"name": "progress.md"})
    versions = history["versions"] if not error else []
    check(len(versions) == 3 and versions[0]["message"] == "delete trace-project/progress.md",
          "history lists the deletion and both appends")
    error, old = first.call("history", {"name": "progress.md", "commit": versions[1]["commit"] if versions else "0000"})
    check(not error and "- step 2" in old["content"], "history returns the content before the deletion")

    error, message = first.call("read_context", {"project": "no-such-project"})
    check(error and "unknown project" in message and "trace-project" in message, "unknown explicit project lists known keys")
    error, message = first.call("write_document", {"name": "../escape.md", "content": "x", "tags": ["a", "b"]})
    check(error and "invalid document name" in message, "path traversal in a document name is rejected")
    error, message = first.call("write_document", {"name": "x.md", "content": "x", "tags": ["single"]})
    check(error and "at least 2 tags" in message, "fewer than 2 tags is rejected")
    error, message = first.call("write_document", {"name": "x.md", "content": "x", "tags": ["single", None]})
    check(error and "at least 2 tags" in message, "a null tag does not count as a tag")

    error, read = first.call("read_documents", {"names": "[\"context.md\"]"})
    check(not error and [doc["name"] for doc in read["documents"]] == ["context.md"], "an array sent JSON-encoded as a string is decoded")
    error, read = first.call("read_documents", {"names": "context.md"})
    check(not error and [doc["name"] for doc in read["documents"]] == ["context.md"], "a lone string is a one-element array")
    error, message = first.call("read_documents", {"names": []})
    check(error and "non-empty" in message, "empty names is an error, not an empty success")
    error, edited = first.call("edit_document", {"name": "context.md",
                                                 "edits": json.dumps([{"oldText": "line two", "newText": "line two, again"}])})
    check(not error, "edits sent JSON-encoded as a string are applied")

    error, written = first.call("write_document", {"project": "other-notes", "name": "notes.md", "content": "x", "tags": ["a", "b"]})
    check(not error and any("created new project 'other-notes'" in w and "trace-project" in w for w in written["warnings"]),
          "a write to an unknown explicit project creates it and warns with the known keys")
    error, written = first.call("write_document", {"project": "other-notes", "name": "notes.md", "content": "y", "tags": ["a", "b"]})
    check(not error and not any("created new project" in w for w in written["warnings"]), "the warning is given only once")
    first.close()

    print("\n--- vault state ---")
    log = git(vault, "log", "--format=%s")
    print(log)
    check(log.splitlines() == ["write other-notes/notes.md", "write other-notes/notes.md", "edit trace-project/context.md",
                               "delete trace-project/progress.md", "append trace-project/progress.md",
                               "append trace-project/progress.md", "edit trace-project/context.md",
                               "write trace-project/context.md", "init memento vault"],
          "every successful mutation is exactly one commit; rejected calls commit nothing")
    registry = json.loads((vault / "projects.json").read_text())
    print(json.dumps(registry, indent=2))
    check(registry["trace-project"]["remotes"] == ["github.com/someone/trace-project"]
          and registry["trace-project"]["paths"] == [str(project)] and "other-notes" not in registry,
          "the first write registered root and remote; an explicit project is not registered")

    print("\n--- prime ---")
    prime = subprocess.run(["java", "-jar", str(JAR), "prime"], input=json.dumps({"cwd": str(project)}),
                           env=env, capture_output=True, text=True, encoding="utf-8")
    print(prime.stdout)
    context = json.loads(prime.stdout)["hookSpecificOutput"]["additionalContext"] if prime.stdout else ""
    check(prime.returncode == 0 and context.startswith("# memento: trace-project") and "line two" in context
          and "Память проекта" in context, "prime injects the current core of the traced project as UTF-8")

    print("\n--- journal ---")
    journal = [json.loads(line) for day in sorted((vault / ".logs").glob("*.jsonl")) for line in day.read_text().splitlines()]
    calls = [event for event in journal if event["mode"] == "server"]
    primes = [event for event in journal if event["mode"] == "prime"]
    print(f"{len(calls)} tool calls, {len(primes)} prime runs")
    check(len(calls) == 23
          and all(event["project"] in ("trace-project", "other-notes") for event in calls if event["outcome"] == "ok")
          and any("revision mismatch" in event.get("error", "") for event in calls)
          and primes and primes[-1]["project"] == "trace-project" and primes[-1]["chars"] > 0,
          "the journal has all 23 tool calls with their project, the rejected edit and the prime run")

    failed = [what for ok, what in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed; trace dir {tmp}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
