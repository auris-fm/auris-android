# Incident evidence — TTS probe teardown uninstalled fm.auris.debug

## Destructive command (exact)
```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=au.com.shiftyjelly.pocketcasts.voicecontrol.ZhTtsSynthesisCheckTest -PpackageNameSuffix="debug" --no-daemon
```
Output: 'Test run failed to complete. Instrumentation run failed due to Process crashed.' + BUILD FAILED in 1m 25s. AGP connected-test teardown uninstalls both the test APK and the target application.

## Timestamps (device local CST)
- 15:15:43 owner attempt (request 2d739314) — BEFORE incident, on the fa854de56 install
- 15:23:29 test process crash (dropbox data_app_crash, pid 21279) — package removed by teardown shortly after
- 15:31:31 reinstall (dumpsys lastUpdateTime), Success via adb install -r

## APK provenance & hashes
- Previously installed (cleared fa854de56): built artifact sha256 d31fa6ec0a8d67ad2dab639f6cbbfe204a44a361e5821c3d8fe8261af5a66fa5; installed base.apk hash verified EXACT at install time (install/installed-fa854de56.json). That artifact was OVERWRITTEN in app/build/outputs by the connected-test rebuild and is NOT retained — no byte comparison possible.
- Currently installed: app/build/outputs/apk/debug/app-debug.apk sha256 b0cd6b498955f71f6ef52e81462e51a98272ff121004b922dd41104be2bedaa2; device base.apk sha256 b0cd6b49… (verified 15:31).
- Source provenance: working tree at c99c27be0. git diff 9f0c90532..c99c27be0 touches ONLY ttsprobe/, artifacts/, install/ and settings.gradle.kts — NO app/module source change between the two provenances. classes.dex sha256 of the current APK: 28500427c83a8539d8cabfd62e274d6fa3e91c6915ca1664efd24e7af92694d9.
- Honest caveat: APK-level hash differs from the prior artifact for an unproven mix of build inputs (APK signing/zip metadata is not reproducible here); equivalence rests on the empty source diff above, not a byte comparison.

## Lost data
- SenseVoice model (files/sensevoice-model/model.int8.onnx 239MB + tokens.txt, Oct 12)
- Sign-in/identity state (auris_cloud.xml user_id, datastore), preferences, files/logs/debug.log history (log copies retained in repo: install/debug-log-postinstall-fa854de56.log 1316 lines; artifacts/refusal-attempt-18193b2d.log)

## Existing restore inventory (READ ONLY)
- No pulled app-data or model backups exist in this workspace or artifacts/ — the model was only ever on-device. Restoration requires on-device re-provisioning.
- Retained evidence: the two log copies above (repo-committed), refusal attempt artifacts, mutation receipts.

## Restoration paths (no device mutation taken)
1. Owner opens app and signs in (Pocket Casts account) — restores account/identity state.
2. Auris voice model: app re-downloads SenseVoice model on first capture readiness (the Oct 12 copy was itself device-provisioned), or can be pushed from an external copy if one exists outside this workspace — none found here.
3. Android datastore/prefs: nothing to restore from; fresh state.
