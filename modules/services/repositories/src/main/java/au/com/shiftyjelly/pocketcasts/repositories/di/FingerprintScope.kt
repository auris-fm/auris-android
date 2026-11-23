package au.com.shiftyjelly.pocketcasts.repositories.di

import javax.inject.Qualifier

/**
 * The scope one fingerprint manager's preparation and decoding run on. Qualified because a plain
 * CoroutineScope binding would be injected into any unrelated class that asks for one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FingerprintScope
