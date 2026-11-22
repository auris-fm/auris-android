# Controls for the RELEASE GATE GRAPH, not for any one script.
#
# A control that runs in CI but is not in `trigger-release.needs` does not gate
# the release: it reds the PR check list while the pipeline still uploads to
# Play. That is how the Play-API-surface job was first added here, and the same
# mistake is available to every future control. So assert the PAIR -- the job
# that runs a control AND the needs edge that makes it binding -- rather than
# the existence of either alone.
require 'yaml'

PASS = [0]
FAIL = [0]
def check(name, ok = nil)
  ok = yield if block_given?
  if ok
    PASS[0] += 1
    puts "ok: #{name}"
  else
    FAIL[0] += 1
    puts "FAIL: #{name}"
  end
end

ROOT = File.expand_path('../..', __dir__)
WF = File.join(ROOT, '.github/workflows')
release = YAML.load_file(File.join(WF, 'auris-release.yml'), aliases: true)
pr      = YAML.load_file(File.join(WF, 'release-script-controls.yml'), aliases: true)
jobs = release.fetch('jobs')
gate = jobs.fetch('trigger-release')
needs = Array(gate['needs'])

check('trigger-release only fires on main') do
  gate['if'].to_s.include?('refs/heads/main')
end

# Every control script in the repo, discovered from disk so a NEW one cannot be
# forgotten by this suite.
controls = Dir[File.join(ROOT, '.buildkite/scripts/*.test.sh')] +
           Dir[File.join(ROOT, '.buildkite/scripts/*.test.rb')]
check('found control scripts to gate on') { controls.size >= 5 }

# Which job runs each control, in each workflow.
runner_of = lambda do |wf, script|
  wf.fetch('jobs').select do |_name, job|
    Array(job['steps']).any? { |s| s['run'].to_s.include?(script) }
  end.keys
end

controls.sort.each do |path|
  rel = path.sub("#{ROOT}/", '')
  name = File.basename(rel)

  # 1. It runs on PRs at all.
  pr_jobs = runner_of.call(pr, rel)
  check("#{name} runs on pull requests") { !pr_jobs.empty? }

  # 2. It runs in the release workflow, and EVERY job that runs it is a
  #    dependency of the trigger. Asserting only (1) would leave a control that
  #    reds the PR but never blocks the upload.
  rel_jobs = runner_of.call(release, rel)
  check("#{name} runs in the release workflow") { !rel_jobs.empty? }
  missing = rel_jobs - needs
  check("#{name} gates the release (needs: #{rel_jobs.join(', ')})") { missing.empty? }
  unless missing.empty?
    puts "      not in trigger-release.needs: #{missing.join(', ')}"
  end
end

# The trigger itself must not depend on a job that does not exist: a typo there
# makes GitHub fail the whole workflow rather than run it.
dangling = needs - jobs.keys
check('every needs entry names a real job') { dangling.empty? }
puts "      dangling: #{dangling.join(', ')}" unless dangling.empty?

puts "#{PASS[0]} passed, #{FAIL[0]} failed"
exit(FAIL[0].zero? ? 0 : 1)
