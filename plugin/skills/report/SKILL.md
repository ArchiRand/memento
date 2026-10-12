---
name: report
description: Build and open the memento health report — projects, document sizes, core budget, stale documents, activity heatmap, tags. Use when the user asks for the memento report, memory health or a memory dashboard.
---

Run with the Bash tool:

java -jar "${CLAUDE_PLUGIN_ROOT}/memento.jar" report

The command prints the path of the generated report.html and opens it in the browser. Tell the user the path. If the command fails, show its stderr.
