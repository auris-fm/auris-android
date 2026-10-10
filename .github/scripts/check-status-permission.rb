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

(d['jobs'] || {}).each do |name, job|
  writes = (job['steps'] || []).any? { |s| (s['run'] || '').include?('/statuses/') }
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
