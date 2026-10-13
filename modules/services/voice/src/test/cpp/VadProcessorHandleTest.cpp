// Host-only test for the JNI VAD processor lifetime guard.
//
// It exists because the fix it covers is a concurrency invariant that no JVM test can observe:
// the Kotlin collector can be parked in a blocking native getter when another thread tears
// capture down, and the guard must make that teardown wait for the in-flight user instead of
// deleting the processor in use. The production header is templated on the processor type, so
// this test instantiates it with a counting dummy and asserts the wait/delete ordering — no
// Oboe, JNI, or Silero needed.
//
// Run it with:  src/test/cpp/run.sh            (optionally: run.sh <build-dir>)

#include "RetireableProcessorHandle.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

namespace {

struct DummyProcessor {
    static std::atomic<int> destroyed;
    DummyProcessor() = default;
    ~DummyProcessor() { destroyed.fetch_add(1); }
};

std::atomic<int> DummyProcessor::destroyed{0};

int gFailures = 0;
void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "  ok  " : "FAIL  ", what);
    if (!ok) ++gFailures;
}

} // namespace

int main() {
    std::printf("native retireable processor handle\n");

    // acquire() on an empty handle returns null rather than a stale pointer.
    {
        RetireableProcessorHandle<DummyProcessor> handle;
        check(handle.acquire() == nullptr, "acquire on an empty handle returns null");
    }

    // publish + acquire returns the same pointer; retire deletes it only after release.
    {
        RetireableProcessorHandle<DummyProcessor> handle;
        auto* dummy = new DummyProcessor();
        handle.publish(dummy);
        DummyProcessor* got = handle.acquire();
        check(got == dummy, "acquire returns the published processor");
        handle.release();
        check(DummyProcessor::destroyed.load() == 0, "processor is alive before retire");
        handle.retire();
        check(DummyProcessor::destroyed.load() == 1, "retire deletes the processor");
        check(handle.acquire() == nullptr, "acquire after retire returns null");
    }

    // A second retire is a no-op, not a double delete.
    {
        RetireableProcessorHandle<DummyProcessor> handle;
        handle.publish(new DummyProcessor());
        const int before = DummyProcessor::destroyed.load();
        handle.retire();
        handle.retire();
        check(DummyProcessor::destroyed.load() == before + 1, "retire is idempotent");
    }

    // retire() blocks while a usage reference is held and completes after release().
    {
        RetireableProcessorHandle<DummyProcessor> handle;
        handle.publish(new DummyProcessor());
        const int baseline = DummyProcessor::destroyed.load();

        std::mutex m;
        std::condition_variable cv;
        bool userHasReference = false;
        bool allowRelease = false;

        std::thread user([&] {
            (void)handle.acquire();
            {
                std::lock_guard<std::mutex> lock(m);
                userHasReference = true;
                cv.notify_all();
            }
            std::unique_lock<std::mutex> lock(m);
            cv.wait(lock, [&] { return allowRelease; });
            handle.release();
        });

        {
            std::unique_lock<std::mutex> lock(m);
            cv.wait(lock, [&] { return userHasReference; });
        }

        std::atomic<bool> retireDone{false};
        std::thread retirer([&] {
            handle.retire();
            retireDone.store(true);
        });

        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        check(!retireDone.load(), "retire blocks while a usage reference is held");
        check(DummyProcessor::destroyed.load() == baseline, "processor is not deleted while in use");

        {
            std::lock_guard<std::mutex> lock(m);
            allowRelease = true;
            cv.notify_all();
        }
        retirer.join();
        user.join();
        check(retireDone.load(), "retire completes once the last user releases");
        check(DummyProcessor::destroyed.load() == baseline + 1, "processor deleted after the user left");
    }

    // retire() waits for every outstanding user, not just the last acquire.
    {
        RetireableProcessorHandle<DummyProcessor> handle;
        handle.publish(new DummyProcessor());
        const int baseline = DummyProcessor::destroyed.load();
        DummyProcessor* a = handle.acquire();
        DummyProcessor* b = handle.acquire();
        check(a == b, "concurrent acquires share the same processor");
        handle.release();
        std::atomic<bool> done{false};
        std::thread t([&] {
            handle.retire();
            done.store(true);
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        check(!done.load(), "retire waits for the remaining user");
        check(DummyProcessor::destroyed.load() == baseline, "processor survives while one user remains");
        handle.release();
        t.join();
        check(done.load(), "retire completes after all users release");
    }

    std::printf(gFailures == 0 ? "all checks passed\n" : "%d CHECK(S) FAILED\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
