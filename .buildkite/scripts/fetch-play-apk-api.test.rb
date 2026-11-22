# Controls for the Play API surface fetch-play-apk.rb depends on.
#
# The automated review could only read this statically: the gem was not
# vendored where it ran, so `list_generatedapks`, `download_generatedapk` and
# `generated_universal_apk.download_id` were unverified names. They are checked
# here against the REAL gem classes from Gemfile.lock -- no hand-made double, so
# a renamed method or a changed JSON property fails instead of being mirrored.
#
# What this does NOT prove: that Play populates download_id for a given build.
# That is a server behaviour, decidable only by a real upload.
# Resolve the gem from wherever it is installed: a checkout-local vendor/bundle
# (what the release steps use) or the runner's default GEM_PATH. Skipping is NOT
# an option -- a control that silently passes when the gem is absent is the
# defect this file exists to remove, so absence fails loudly with the remedy.
libs = Dir[File.expand_path("../../vendor/bundle/ruby/*/gems/*/lib", __dir__)]
$LOAD_PATH.unshift(*libs) unless libs.empty?
begin
  require 'google/apis/androidpublisher_v3'
rescue LoadError => e
  warn "FAIL: cannot load google-apis-androidpublisher_v3 (#{e.message})."
  warn "      Run `bundle install` first; this control needs the REAL gem."
  exit 1
end

PASS = [0]
FAIL = [0]
def check(name)
  ok = begin
    yield
  rescue StandardError => e
    puts "FAIL: #{name} (#{e.class}: #{e.message})"
    FAIL[0] += 1
    return
  end
  if ok
    PASS[0] += 1
    puts "ok: #{name}"
  else
    FAIL[0] += 1
    puts "FAIL: #{name}"
  end
end

S = Google::Apis::AndroidpublisherV3

# The gem under test must be the one Gemfile.lock pins. Otherwise a lockfile bump
# leaves this control green against an OLD gem while the release step uses the
# new, unverified one -- the control would certify a version nobody ships.
lock = File.read(File.expand_path('../../Gemfile.lock', __dir__))
pinned = lock[/^\s{4}google-apis-androidpublisher_v3 \(([0-9.]+)\)/, 1]
if pinned.nil?
  warn 'FAIL: could not read the google-apis-androidpublisher_v3 pin from Gemfile.lock'
  exit 1
end
# Loading via $LOAD_PATH (the vendored-bundle path) registers no loaded_spec, so
# fall back to the directory the library was required from. The gem's VERSION
# constant cannot serve: it is the API version "V3", not the gem version.
loaded = Gem.loaded_specs['google-apis-androidpublisher_v3']&.version&.to_s
if loaded.nil?
  path = $LOADED_FEATURES.find { |f| f.include?('google-apis-androidpublisher_v3-') }
  loaded = path && path[/google-apis-androidpublisher_v3-([0-9.]+)/, 1]
end
if loaded != pinned
  warn "FAIL: loaded gem #{loaded.inspect} != Gemfile.lock pin #{pinned.inspect}."
  warn "      Install the pinned version: gem install google-apis-androidpublisher_v3 -v #{pinned}"
  exit 1
end
puts "ok: gem under test is the locked pin (#{pinned})"
PASS[0] += 1

svc = S::AndroidPublisherService.new

check("list_generatedapks exists") { svc.respond_to?(:list_generatedapks) }
check("download_generatedapk exists") { svc.respond_to?(:download_generatedapk) }

# Positional order matters: the fetcher passes (package, versionCode) and
# (package, versionCode, downloadId) positionally, so a reordering would send
# the wrong values rather than raise.
check("list takes (package_name, version_code) positionally") do
  svc.method(:list_generatedapks).parameters.first(2) ==
    [[:req, :package_name], [:req, :version_code]]
end
check("download takes (package_name, version_code, download_id) positionally") do
  svc.method(:download_generatedapk).parameters.first(3) ==
    [[:req, :package_name], [:req, :version_code], [:req, :download_id]]
end
check("download accepts the download_dest keyword the fetcher uses") do
  svc.method(:download_generatedapk).parameters.include?([:key, :download_dest])
end

# Build the response from the gem's OWN classes and round-trip it through its
# JSON representation, so the wire property names are exercised too.
resp = S::GeneratedApksListResponse.new(
  generated_apks: [
    S::GeneratedApksPerSigningKey.new(
      certificate_sha256_hash: 'AA:BB',
      generated_universal_apk: S::GeneratedUniversalApk.new(download_id: 'dl-1'),
    ),
  ],
)
check("response exposes generated_apks grouped by signing key") do
  resp.generated_apks.first.certificate_sha256_hash == 'AA:BB'
end
check("group exposes generated_universal_apk.download_id") do
  resp.generated_apks.first.generated_universal_apk.download_id == 'dl-1'
end
check("wire names round-trip (generatedApks / certificateSha256Hash / downloadId)") do
  back = S::GeneratedApksListResponse.from_json(resp.to_json)
  back.generated_apks.first.generated_universal_apk.download_id == 'dl-1' &&
    back.generated_apks.first.certificate_sha256_hash == 'AA:BB'
end

# A group with no universal APK must be distinguishable, because the fetcher
# selects on `generated_universal_apk && ...download_id` rather than taking the
# first group -- after a key rotation both keys' sets exist.
check("a group without a universal APK reads as nil, not an error") do
  S::GeneratedApksPerSigningKey.new(certificate_sha256_hash: 'CC:DD')
   .generated_universal_apk.nil?
end

puts "#{PASS[0]} passed, #{FAIL[0]} failed"
exit(FAIL[0].zero? ? 0 : 1)
