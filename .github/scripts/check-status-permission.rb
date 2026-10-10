#!/usr/bin/env ruby
# The read-back check @spec adopted as the fifth: every job that writes a commit status must
# DECLARE statuses: write, because GitHub sets absent permissions to none and the failure
# looks exactly like success.
require 'yaml'
path = ARGV[0] || '.github/workflows/claude-code-review.yml'
d = YAML.load_file(path)
fails = []
d["jobs"].each do |name, job|
  writes = (job["steps"] || []).any? { |s| (s["run"] || "").include?("/statuses/") }
  next unless writes
  perms = job["permissions"]
  if perms.nil?
    fails << "#{name}: writes a status but declares no permissions block (defaults apply — verify)"
  elsif perms["statuses"] != "write"
    fails << "#{name}: writes a status but does not declare statuses: write (has #{perms.inspect})"
  end
end
if fails.empty?
  puts "OK: every status-writing job declares statuses: write"
  exit 0
else
  fails.each { |f| puts "FAIL: #{f}" }
  exit 1
end
