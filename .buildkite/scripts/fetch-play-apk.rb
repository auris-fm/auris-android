#!/usr/bin/env ruby
# Fetch Play's signed universal APK for a version code via the Play Developer
# API (generatedapks). Uses the same service account as the upload.
# Usage: fetch-play-apk.rb <service-account.json> <versionCode> <out.apk>
# Prints the signing-key certificate SHA-256 Play reports, for the caller's
# pin check. Exits non-zero on any failure; never leaves a partial output
# (downloads to <out>.part, renames on success).
require 'google/apis/androidpublisher_v3'
require 'googleauth'

PACKAGE = 'fm.auris'.freeze

def die(msg)
  warn "FAIL: #{msg}"
  exit 1
end

key_file, version_code, out_path = ARGV
die 'usage: fetch-play-apk.rb <service-account.json> <versionCode> <out.apk>' unless key_file && version_code && out_path
die "service account file not found: #{key_file}" unless File.file?(key_file)
begin
  version_code = Integer(version_code)
rescue ArgumentError
  die "versionCode not an integer: #{version_code}"
end

scope = 'https://www.googleapis.com/auth/androidpublisher'
auth = Google::Auth::ServiceAccountCredentials.make_creds(json_key_io: File.open(key_file), scope: scope)
service = Google::Apis::AndroidpublisherV3::AndroidPublisherService.new
service.authorization = auth

resp = service.list_generatedapks(PACKAGE, version_code)
groups = resp.generated_apks || []
die "no generated APKs for versionCode #{version_code}" if groups.empty?

group = groups.find { |g| g.generated_universal_apk && g.generated_universal_apk.download_id }
die "no universal APK among the generated sets for #{version_code}" unless group

puts "Play app-signing certificate SHA-256: #{group.certificate_sha256_hash}"

tmp = "#{out_path}.part"
begin
  File.open(tmp, 'wb') do |f|
    service.download_generatedapk(PACKAGE, version_code, group.generated_universal_apk.download_id, download_dest: f)
  end
  die 'downloaded file is empty' unless File.size(tmp) > 0
  File.rename(tmp, out_path)
rescue StandardError => e
  File.delete(tmp) if File.exist?(tmp)
  die "download failed: #{e.message}"
end
puts "Fetched Play universal APK for versionCode #{version_code}: #{out_path} (#{File.size(out_path)} bytes)"
