#!/usr/bin/env ruby
# Falsifying cases for check-status-permission.rb.
#
# The point of these is the SCOPE CLAIM, not just the pass/fail. The check reads an INLINE
# `.../statuses/...` call and REFUSES a shape it cannot read. That distinction only holds if
# something fails when it stops holding — otherwise a future editor can narrow the detector
# and the check keeps reporting OK, which is exactly how the first version went inert.
#
# Run: ruby .github/scripts/check-status-permission.test.rb
require 'yaml'
require 'tmpdir'

CHECK = File.expand_path('check-status-permission.rb', __dir__)

# Run the check against an inline workflow body and return [exit_status, output].
def run_check(workflow)
  Dir.mktmpdir do |dir|
    path = File.join(dir, 'wf.yml')
    File.write(path, workflow)
    out = `ruby #{CHECK} #{path} 2>&1`
    return [$?.exitstatus, out]
  end
end

def job(body, perms:)
  <<~YML
    jobs:
      claude-review:
        permissions:
    #{perms.map { |p| "      #{p}\n" }.join.rstrip}
        steps:
    #{body}
  YML
end

INLINE = "      - name: Record\n        run: gh api \"repos/\${REPOSITORY}/statuses/\${HEAD_SHA}\" -f state=success\n"
SCRIPT = "      - name: finish\n        run: node \"$RUNNER_TEMP/claude-review.mjs\" finish\n"
NOTHING = "      - run: ./gradlew spotlessCheck\n"
# A call that has been disabled by commenting it out — the case the naive detector misread.
COMMENTED_OUT = "      - name: review\n        run: |\n          set -euo pipefail\n          # gh api \"repos/\${REPOSITORY}/statuses/\${HEAD_SHA}\" -f state=success\n          echo disabled\n"
# An indented comment, to pin that leading whitespace is handled.
INDENTED_COMMENT = "      - name: review\n        run: |\n          if true; then\n            # gh api \"repos/\${REPOSITORY}/statuses/\${HEAD_SHA}\"\n            echo skipped\n          fi\n"

# Two jobs: one really writes a status, one writes nothing. The writing job must be checked
# and the silent job must NOT be reported — otherwise the control becomes noise and gets muted.
MIXED = <<~YML
  jobs:
    claude-review:
      permissions:
        contents: read
        statuses: write
      steps:
  #{INLINE.gsub(/^/, '  ')}  lint:
      permissions:
        contents: read
      steps:
  #{NOTHING.gsub(/^/, '  ')}
YML

CASES = {
  'inline + permission declared' => [job(INLINE, perms: ['contents: read', 'statuses: write']), 0, /OK/],
  'inline + permission ABSENT (the shipped defect)' => [job(INLINE, perms: ['contents: read']), 1, /does not declare statuses: write/],
  'inline + NO permissions block' => [job(INLINE, perms: []), 1, /declares no permissions block/],
  'a silent job alongside a writer is NOT reported (negative control)' => [MIXED, 0, /OK: 1 status-writing job/],
  # THE FIX'S OWN FALSIFIER, from the merged file: a call that has been COMMENTED OUT is not an
  # active one. The naive text match read it as a writer and demanded the permission for disabled
  # code — the guard's defect shape one level down. If the comment-scoping is removed, this fails.
  'a COMMENTED-OUT status call is not a writer' => [job(COMMENTED_OUT, perms: ['contents: read']), 1, /detector did not match/],
  'a real call after an INDENTED comment is still a writer' => [job(INDENTED_COMMENT + INLINE, perms: ['contents: read']), 1, /does not declare statuses: write/],
  'no status writer anywhere (positive control)' => [job(NOTHING, perms: ['contents: read']).sub(/claude-review/, 'lint'), 1, /detector did not match/],
  # THE SCOPE CLAIM, AS A CASE. If a future editor narrows the detector, or widens the scope
  # note without widening the detector, this pair stops producing two DIFFERENT messages.
  'script-invoked + permission ABSENT -> REFUSED, not certified' => [job(SCRIPT, perms: ['contents: read']), 1, /detector did not match/],
  'script-invoked + permission declared -> still REFUSED (scope, not a pass)' => [job(SCRIPT, perms: ['contents: read', 'statuses: write']), 1, /detector did not match/]
}.freeze

failures = []
CASES.each do |name, (wf, want_status, want_msg)|
  status, out = run_check(wf)
  if status != want_status || !out.match?(want_msg)
    failures << "#{name}\n    want status=#{want_status} msg=#{want_msg.inspect}\n    got  status=#{status} out=#{out.strip.lines.last}"
  end
end

if failures.empty?
  puts "OK: #{CASES.size}/#{CASES.size} cases, including the scope claim as a failing pair"
  exit 0
else
  puts "FAIL: #{failures.size}/#{CASES.size} cases"
  failures.each { |f| puts "  - #{f}" }
  exit 1
end
