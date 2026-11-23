package au.com.shiftyjelly.pocketcasts.repositories.di

import javax.inject.Qualifier

/**
 * Where transcript references are served. Qualified because a plain String binding would be
 * injected into any unrelated class that asks for one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TranscriptReferenceBaseUrl
