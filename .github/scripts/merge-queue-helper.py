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


def job_condition(path, key):
    """A job's own `if:`, as one line, or empty when it has none."""
    body = jobs(path).get(key)
    if body is None:
        return ""
    m = re.search(r"^    if:\s*(.*(?:\n      .*)*)", "\n".join(body), re.M)
    return " ".join(m.group(1).split()) if m else ""


def condition_excludes_queue(cond):
    """True when this condition cannot hold for a merge group.

    Two shapes are recognised, both real ways to write it: a condition that names only pull
    requests, and one that explicitly rules a merge group out with no alternative. Anything
    else is left alone rather than guessed at.
    """
    if not cond:
        return False
    # `!= 'pull_request'` admits a merge group; only an equality gates it out.
    gated_to_pr = re.search(r"event_name\s*==\s*'pull_request'", cond)
    names_mg = "merge_group" in cond
    if gated_to_pr and not names_mg:
        return True
    # An explicit exclusion rules the queue out even inside an `||`: the other arm is a
    # pull request, so neither can hold for a merge group.
    if re.search(r"event_name\s*!=\s*'merge_group'", cond):
        return True
    return False


def queue_excluded_jobs(path):
    """Job keys whose own `if:` cannot be true for a merge group.

    A job that never runs reports no check, so a required name behind such a condition is
    a check that never arrives. Two shapes are recognised, and both are real ways to write
    it: a condition that names only pull requests, and one that explicitly rules a merge
    group out. Anything else is left alone rather than guessed at.
    """
    return [key for key in jobs(path)
            if condition_excludes_queue(job_condition(path, key))]


def queue_reporting_jobs_excluded(path):
    """Jobs that report a check for a queue build but whose condition rules it out.

    A job with no condition of its own runs whenever the workflow does, so it is fine. A job
    whose condition mentions a merge group is meant to run there. What is left is a job that
    would report in the queue and cannot: one gated to pull requests only, or one that rules
    a merge group out. Jobs already known to be pull-request-only by the workflow's own
    triggers are not this function's business.
    """
    ls = listeners(path)
    if "merge_group" not in ls:
        return []
    out = []
    for key in jobs(path):
        cond = job_condition(path, key)
        if not cond:
            continue
        if condition_excludes_queue(cond):
            out.append(key)
    return out


if __name__ == "__main__":
    command, path = sys.argv[1], sys.argv[2]
    if command == "listeners":
        print(",".join(listeners(path)))
    elif command == "calling-job-key":
        print(calling_job_key(path))
    elif command == "queue-reporting-jobs-excluded":
        print(",".join(queue_reporting_jobs_excluded(path)))
    elif command == "job-condition":
        print(job_condition(path, sys.argv[3]))
    elif command == "condition-excludes-queue":
        sys.exit(0 if condition_excludes_queue(sys.argv[2]) else 1)
    elif command == "queue-excluded-jobs":
        print(",".join(queue_excluded_jobs(path)))
    elif command == "job-check-name":
        print(job_check_name(path))
    elif command == "merge-group-only":
        sys.exit(0 if listeners(path) == ["merge_group"] else 1)
    else:
        sys.exit("unknown command: " + command)
