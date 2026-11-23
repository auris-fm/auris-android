#!/usr/bin/env python3
"""Structural questions about the workflows, for merge-queue.test.sh.

Kept in a file of its own so the shell control never has to carry a long program
inside a here-document, where a single mis-indented line is invisible.
"""
import re
import sys


def listeners(path):
    """The workflow's triggers, in order, from its `on:` block."""
    out, in_on = [], False
    for line in open(path).read().split("\n"):
        if line.startswith("on:"):
            flow = re.match(r"^on:\s*\[(.*)\]", line)
            if flow:
                out = [p.strip() for p in flow.group(1).split(",") if p.strip()]
                in_on = False
            else:
                in_on = True
            continue
        if in_on and line and not line[0].isspace() and not line.startswith("#"):
            in_on = False
        if in_on:
            m = re.match(r"^\s+([a-z_]+):\s*$", line)
            if m:
                out.append(m.group(1))
    return out


if __name__ == "__main__":
    command, path = sys.argv[1], sys.argv[2]
    if command == "listeners":
        print(",".join(listeners(path)))
    elif command == "merge-group-only":
        sys.exit(0 if listeners(path) == ["merge_group"] else 1)
    else:
        sys.exit("unknown command: " + command)
