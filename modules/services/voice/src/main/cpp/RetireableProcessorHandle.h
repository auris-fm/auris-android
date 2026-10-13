#ifndef POCKETCASTS_RETIREABLE_PROCESSOR_HANDLE_H
#define POCKETCASTS_RETIREABLE_PROCESSOR_HANDLE_H

#include <condition_variable>
#include <mutex>

/**
 * Owns a processor pointer whose users run on other threads, and retires it only once
 * every in-flight user has left.
 *
 * The Kotlin VAD collector blocks inside native calls (nativeWaitForVadEvent /
 * nativeGetSpeechPcm*) while holding a raw NativeVadProcessor*. Coroutine cancellation does
 * not preempt a blocking JNI call, so a teardown on another thread that deleted the processor
 * while a getter was executing would be a use-after-free. This handle makes the teardown wait:
 *
 *   - acquire() takes a usage reference and returns the live pointer (or null).
 *   - release() returns the reference.
 *   - retire() blocks until no usage reference remains, then deletes the pointer and clears it.
 *
 * Acquisition after retire() returns null, which the JNI getters surface as "no processor".
 * publish() is the only way to set a pointer; callers serialize publish/retire externally.
 */
template <typename Processor>
class RetireableProcessorHandle {
public:
    RetireableProcessorHandle() = default;
    RetireableProcessorHandle(const RetireableProcessorHandle&) = delete;
    RetireableProcessorHandle& operator=(const RetireableProcessorHandle&) = delete;
    ~RetireableProcessorHandle() { retire(); }

    /** Returns the live processor with a usage reference held, or null. Caller must release(). */
    Processor* acquire() {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mProcessor == nullptr) {
            return nullptr;
        }
        ++mUsers;
        return mProcessor;
    }

    /** Returns the usage reference taken by acquire(). */
    void release() {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mUsers > 0 && --mUsers == 0) {
            mIdle.notify_all();
        }
    }

    /** Publishes a fully started processor. The previous one must already have been retired. */
    void publish(Processor* processor) {
        std::lock_guard<std::mutex> lock(mMutex);
        mProcessor = processor;
    }

    /** Waits for in-flight users to leave, then deletes the processor. Idempotent. */
    void retire() {
        std::unique_lock<std::mutex> lock(mMutex);
        mIdle.wait(lock, [this] { return mUsers == 0; });
        delete mProcessor;
        mProcessor = nullptr;
    }

private:
    std::mutex mMutex;
    std::condition_variable mIdle;
    int mUsers = 0;
    Processor* mProcessor = nullptr;
};

#endif // POCKETCASTS_RETIREABLE_PROCESSOR_HANDLE_H
