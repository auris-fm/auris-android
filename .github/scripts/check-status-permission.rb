#!/usr/bin/env ruby
# The read-back check @spec adopted as the fifth: every job that writes a commit status must
# DECLARE statuses: write, because GitHub sets absent permissions to `none` and a rejected
# write fails the job.
#
# The defect is CROSS-ARTIFACT: neither the step nor the permissions block shows it alone,
# only their relationship does. So this asserts the relationship.
#
# The PRIMARY repair is the positive control below, not a wider pattern. A text-keyed
# detector is exactly as strong as the stability of the text it keys on — widening amplifies
# the detector without making it honest, and a widened pattern verified against no real file
# is the same inert shape in a new costume. What makes it honest is asserting that the
# detector MATCHED, so an unmatched pattern is a loud failure instead of a silent pass.
#
# Scope note: this keys on an INLINE `.../statuses/...` call, which is this repo's shape.
# A workflow that writes through an invoked script is NOT covered here — see the positive
# control, which reports that as "detector did not match" rather than as a pass. That is
# deliberate: claiming coverage of a shape this does not read would be worse than the gap.
require 'yaml'

path = ARGV[0] || '.github/workflows/claude-code-review.yml'
d = YAML.load_file(path)
fails = []
matched = 0

# A `run:` body is raw text that INCLUDES its own shell comments, so matching `/statuses/`
# anywhere in it makes a step that merely MENTIONS the endpoint satisfy the detector. Measured
# on the merged file: a commented-out call
#
#     # gh api "repos/${REPOSITORY}/statuses/${HEAD_SHA}" -f state=success
#
# was read as an ACTIVE one, so a job would be required to declare `statuses: write` for code
# that does nothing. That is the guard's own defect shape one level down — a disabled call read
# as an enabled one — so the detector accepts only a line that IS A COMMAND.
#
# This is not a YAML parse and not a trade between the two directions: it narrows the accepted
# shape to the shape this repo actually uses, distinguishing command from comment inside a
# string the parser has already handed us.
def writes_status?(run)
  run.to_s.lines.any? do |line|
    stripped = line.sub(/\A\s+/, '')
    !stripped.start_with?('#') && stripped.include?('/statuses/')
  end
end

(d['jobs'] || {}).each do |name, job|
  writes = (job['steps'] || []).any? { |s| writes_status?(s['run']) }
  next unless writes

  matched += 1
  perms = job['permissions']
  if perms.nil?
    fails << "#{name}: writes a status but declares no permissions block (defaults apply — verify)"
  elsif perms['statuses'] != 'write'
    fails << "#{name}: writes a status but does not declare statuses: write (has #{perms.inspect})"
  end
end

# The positive control. Without it a detector that matches nothing reports OK — which is how
# the first version of this check, and iOS's, both went inert. It also makes the scope gap
# above honest: a script-written status is refused rather than silently accepted.
if matched.zero?
  puts 'FAIL: no status-writing job detected — the detector did not match, so this check ' \
       'verified nothing. Fix the detector before trusting a pass.'
  exit 1
end

if fails.empty?
  puts "OK: #{matched} status-writing job(s) found, and all declare statuses: write"
  exit 0
else
  fails.each { |f| puts "FAIL: #{f}" }
  exit 1
end
