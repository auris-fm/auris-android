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


def jobs(path):
    """The workflow's job keys, in order, with their bodies.

    Only the `jobs:` block is read: a trigger key at the same indentation would otherwise
    be taken for a job, which is how this helper first reported the wrong name.
    """
    out, current, in_jobs = {}, None, False
    for line in open(path).read().split("\n"):
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if in_jobs and line and not line[0].isspace() and not line.startswith("#"):
            in_jobs = False
        if not in_jobs:
            continue
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            current = m.group(1)
            out[current] = []
            continue
        if current is not None:
            out[current].append(line)
    return out


def calling_job_key(path):
    """The key of the job that calls a reusable workflow, which prefixes its check name."""
    for key, body in jobs(path).items():
        if any(l.startswith("    uses:") for l in body):
            return key
    return ""


def job_check_name(path):
    """The check name a job reports: its `name:` when it has one, otherwise its key."""
    for key, body in jobs(path).items():
        text = "\n".join(body)
        m = re.search(r'^    name:\s*"?(.+?)"?\s*$', text, re.M)
        return m.group(1) if m else key
    return ""


if __name__ == "__main__":
    command, path = sys.argv[1], sys.argv[2]
    if command == "listeners":
        print(",".join(listeners(path)))
    elif command == "calling-job-key":
        print(calling_job_key(path))
    elif command == "job-check-name":
        print(job_check_name(path))
    elif command == "merge-group-only":
        sys.exit(0 if listeners(path) == ["merge_group"] else 1)
    else:
        sys.exit("unknown command: " + command)
